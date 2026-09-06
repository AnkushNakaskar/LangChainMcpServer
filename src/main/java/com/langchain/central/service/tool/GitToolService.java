package com.langchain.central.service.tool;

import com.google.inject.Singleton;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only Git tools used to gather the repository context needed for code review.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class GitToolService implements ToolService {

    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final Pattern SAFE_REVISION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@~^{}-]*");

    private final Path repositoryPath;

    public GitToolService() {
        this(resolveRepositoryPath());
    }

    GitToolService(final Path repositoryPath) {
        this.repositoryPath = repositoryPath.toAbsolutePath().normalize();
    }

    @Tool("Get the current Git branch and working tree status for code review")
    public String getGitStatus() {
        return runGit("Working tree is clean.", "status", "--short", "--branch");
    }

    @Tool("Get unstaged changes in the Git working tree as a patch")
    public String getWorkingTreeDiff() {
        return runGit("No unstaged changes.", "diff", "--no-ext-diff", "--unified=3", "--");
    }

    @Tool("Get staged Git changes as a patch")
    public String getStagedDiff() {
        return runGit(
                "No staged changes.",
                "diff",
                "--cached",
                "--no-ext-diff",
                "--unified=3",
                "--");
    }

    @Tool("Get the patch between two Git revisions for code review")
    public String getDiffBetweenRevisions(
            @P("Base branch, tag, or commit SHA") final String baseRevision,
            @P("Head branch, tag, or commit SHA") final String headRevision) {
        return runGit(
                "No changes between the revisions.",
                "diff",
                "--no-ext-diff",
                "--unified=3",
                requireRevision(baseRevision),
                requireRevision(headRevision),
                "--");
    }

    @Tool("List files changed between two Git revisions with their change status")
    public String listChangedFiles(
            @P("Base branch, tag, or commit SHA") final String baseRevision,
            @P("Head branch, tag, or commit SHA") final String headRevision) {
        return runGit(
                "No files changed between the revisions.",
                "diff",
                "--name-status",
                requireRevision(baseRevision),
                requireRevision(headRevision),
                "--");
    }

    @Tool("Get recent Git commits to understand repository history")
    public String getRecentCommits(
            @P("Number of commits to return, from 1 to 100") final String maxCount) {
        return runGit(
                "No commits found.",
                "log",
                "--max-count=" + maxCount,
                "--date=iso-strict",
                "--pretty=format:%h%x09%ad%x09%an%x09%s");
    }

    @Tool("Show a Git commit with its metadata, file statistics, and patch")
    public String showCommit(
            @P("Branch, tag, or commit SHA to inspect") final String revision) {
        return runGit(
                "Commit contains no displayable changes.",
                "show",
                "--no-ext-diff",
                "--format=fuller",
                "--stat",
                "--patch",
                requireRevision(revision),
                "--");
    }

    private String runGit(final String emptyResult, final String... arguments) {
        if (!Files.isDirectory(repositoryPath)) {
            throw new IllegalStateException(
                    "Git repository directory does not exist: " + repositoryPath);
        }

        final List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(List.of(arguments));
        log.info("Running Git tool command in {}: {}", repositoryPath, command);

        final ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repositoryPath.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");

        try {
            final Process process = processBuilder.start();
            final CompletableFuture<String> output =
                    CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.join();
                throw new IllegalStateException(
                        "Git command timed out after " + COMMAND_TIMEOUT_SECONDS + " seconds");
            }

            final String text = output.join().trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        text.isEmpty() ? "Git command failed with no output" : text);
            }
            return text.isEmpty() ? emptyResult : text;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to execute Git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command was interrupted", e);
        }
    }

    private String readOutput(final InputStream inputStream) {
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (totalBytes < MAX_OUTPUT_BYTES) {
                    final int bytesToKeep = Math.min(bytesRead, MAX_OUTPUT_BYTES - totalBytes);
                    output.write(buffer, 0, bytesToKeep);
                }
                totalBytes += bytesRead;
            }
            final String text = output.toString(StandardCharsets.UTF_8);
            return totalBytes > MAX_OUTPUT_BYTES
                    ? text + System.lineSeparator() + "[Output truncated at 1 MiB]"
                    : text;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Git output: " + e.getMessage(), e);
        }
    }

    private String requireRevision(final String revision) {
        if (revision == null || !SAFE_REVISION.matcher(revision).matches()) {
            throw new IllegalArgumentException(
                    "Revision must be a branch, tag, or commit SHA without command options");
        }
        return revision;
    }

    private static Path resolveRepositoryPath() {
        final String configuredPath = System.getProperty(
                "git.repository.path",
                System.getenv().getOrDefault("GIT_REPOSITORY_PATH", System.getProperty("user.dir")));
        return Path.of(configuredPath);
    }
}
