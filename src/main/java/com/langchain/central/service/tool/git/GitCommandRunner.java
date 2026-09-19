package com.langchain.central.service.tool.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs Git commands against a named repository directory.
 *
 * <p>Separated from the tools themselves so that process handling, timeouts and output limits are
 * defined once, and each tool is left as a description of the Git command it needs.
 */
@Slf4j
public class GitCommandRunner {

    public static final int COMMAND_TIMEOUT_SECONDS = 30;
    public static final int NETWORK_TIMEOUT_SECONDS = 300;

    private static final int STREAM_DRAIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    /** {@code git grep} reports "nothing matched" as exit code 1, not as an error. */
    private static final int NO_MATCHES_EXIT_CODE = 1;

    /**
     * Runs a Git command and fails the tool call if Git does.
     *
     * @param repositoryPath directory the command runs in
     * @param emptyResult text returned when Git succeeds with no output
     * @param arguments Git arguments, already validated by the caller
     */
    public String run(
            final Path repositoryPath, final String emptyResult, final String... arguments) {
        return run(repositoryPath, COMMAND_TIMEOUT_SECONDS, emptyResult, arguments);
    }

    public String run(
            final Path repositoryPath,
            final int timeoutSeconds,
            final String emptyResult,
            final String... arguments) {
        final GitResult result = execute(repositoryPath, timeoutSeconds, arguments);
        if (!result.isSuccessful()) {
            throw new IllegalStateException(
                    result.getOutput().isEmpty()
                    ? "Git command failed with no output: git " + String.join(" ", arguments)
                    : result.getOutput());
        }
        return result.getOutput().isEmpty() ? emptyResult : result.getOutput();
    }

    /**
     * Runs a search command, where Git signals "no matches" with exit code 1 rather than with an
     * error. Reporting that as a failure would tell a review agent the search was broken when in
     * fact the answer was simply "nothing found".
     */
    public String runSearch(
            final Path repositoryPath, final String emptyResult, final String... arguments) {
        final GitResult result = execute(repositoryPath, COMMAND_TIMEOUT_SECONDS, arguments);
        if (result.getExitCode() == NO_MATCHES_EXIT_CODE && result.getOutput().isEmpty()) {
            return emptyResult;
        }
        if (!result.isSuccessful()) {
            throw new IllegalStateException(
                    result.getOutput().isEmpty()
                    ? "Git search failed with no output: git " + String.join(" ", arguments)
                    : result.getOutput());
        }
        return result.getOutput().isEmpty() ? emptyResult : result.getOutput();
    }

    /** Runs a command whose failure is an expected outcome, such as probing for a ref. */
    public GitResult tryRun(
            final Path repositoryPath, final int timeoutSeconds, final String... arguments) {
        return execute(repositoryPath, timeoutSeconds, arguments);
    }

    private GitResult execute(
            final Path repositoryPath, final int timeoutSeconds, final String... arguments) {
        if (!Files.isDirectory(repositoryPath)) {
            throw new IllegalStateException(
                    "Git repository directory does not exist: " + repositoryPath);
        }

        final List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(List.of(arguments));
        log.info("Running Git command in {}: {}", repositoryPath, command);

        final ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repositoryPath.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
        processBuilder.environment().put("GIT_ASKPASS", "echo");
        processBuilder.environment().putIfAbsent(
                "GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=15");

        Process process = null;
        try {
            process = processBuilder.start();
            final Process started = process;
            final CompletableFuture<String> output =
                    CompletableFuture.supplyAsync(() -> readOutput(started.getInputStream()));

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "Git command timed out after " + timeoutSeconds + " seconds: " + command);
            }
            return new GitResult(process.exitValue(), drain(output).trim());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to execute Git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command was interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Bounded wait so that a reader blocked on a stream that never closes cannot hang the tool call
     * after the process itself has been killed.
     */
    private String drain(final CompletableFuture<String> output) {
        try {
            return output.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            output.cancel(true);
            return "";
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime
                  ? runtime
                  : new IllegalStateException("Unable to read Git output", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading Git output", e);
        }
    }

    /**
     * Keeps the first {@link #MAX_OUTPUT_BYTES} and says plainly how much was dropped, so a review
     * is never written against a silently truncated diff.
     */
    private String readOutput(final InputStream inputStream) {
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            long totalBytes = 0;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (totalBytes < MAX_OUTPUT_BYTES) {
                    final int bytesToKeep =
                            (int) Math.min(bytesRead, MAX_OUTPUT_BYTES - totalBytes);
                    output.write(buffer, 0, bytesToKeep);
                }
                totalBytes += bytesRead;
            }
            final String text = output.toString(StandardCharsets.UTF_8);
            if (totalBytes <= MAX_OUTPUT_BYTES) {
                return text;
            }
            return text
                    + System.lineSeparator()
                    + "[OUTPUT TRUNCATED. Showing "
                    + MAX_OUTPUT_BYTES
                    + " of "
                    + totalBytes
                    + " bytes. The remainder was NOT reviewed. Re-run per file using "
                    + "get_merge_request_file_diff, or narrow the search.]";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Git output: " + e.getMessage(), e);
        }
    }

    /** Exit code and combined output of one Git invocation. */
    @Value
    public static class GitResult {
        int exitCode;
        String output;

        public boolean isSuccessful() {
            return exitCode == 0;
        }
    }
}
