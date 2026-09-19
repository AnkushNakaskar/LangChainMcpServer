package com.langchain.central.service.tool.git;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides a local clone for any repository a reviewer names.
 *
 * <p>Reviews are requested by link, so the tools cannot assume the repository the server was
 * started in. A remote repository is cloned once into a workspace directory and reused on later
 * calls, which keeps the first tool call of a review the only slow one. A repository given as a
 * local path is used in place and is never fetched into, so a reviewer's own working tree is left
 * untouched.
 */
@Slf4j
@Singleton
public class GitWorkspace {

    /** Blobless clone: full history for diff and blame, file contents fetched only when read. */
    private static final List<String> PARTIAL_CLONE_ARGUMENTS =
            List.of("clone", "--filter=blob:none", "--no-tags");

    /** Used when the remote or the transport refuses a partial clone. */
    private static final List<String> FULL_CLONE_ARGUMENTS = List.of("clone", "--no-tags");

    private static final Duration FETCH_INTERVAL = Duration.ofMinutes(5);

    private final GitCommandRunner commandRunner;
    private final Path workspaceRoot;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFetch = new ConcurrentHashMap<>();

    @Inject
    public GitWorkspace(final GitCommandRunner commandRunner) {
        this(commandRunner, defaultWorkspaceRoot());
    }

    GitWorkspace(final GitCommandRunner commandRunner, final Path workspaceRoot) {
        this.commandRunner = commandRunner;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Returns a local clone of the repository, cloning or refreshing it as needed.
     *
     * @param repository clone URL, project URL, or path of an existing local clone
     * @param forceFetch true to contact the remote even if it was fetched recently
     */
    public Path checkout(final String repository, final boolean forceFetch) {
        final GitRepositoryReference reference = GitRepositoryReference.parse(repository);
        if (reference.isLocal()) {
            return verifyGitDirectory(reference.getLocalPath());
        }
        synchronized (locks.computeIfAbsent(reference.getWorkspaceKey(), key -> new Object())) {
            final Path clonePath = workspaceRoot.resolve(reference.getWorkspaceKey());
            if (Files.isDirectory(clonePath.resolve(".git"))) {
                fetchIfStale(reference, clonePath, forceFetch);
                return clonePath;
            }
            return clone(reference, clonePath);
        }
    }

    /** Fetches the remote regardless of how recently it was last contacted. */
    public String fetch(final String repository) {
        final Path clonePath = checkout(repository, false);
        return fetchNow(clonePath);
    }

    /** Where a repository is checked out, for reporting back to the reviewer. */
    public Path locate(final String repository) {
        return checkout(repository, false);
    }

    private Path clone(final GitRepositoryReference reference, final Path clonePath) {
        final Path stagingPath = clonePath.resolveSibling(clonePath.getFileName() + ".partial");
        createDirectories(workspaceRoot);

        GitCommandRunner.GitResult result = null;
        for (final String location : reference.cloneLocations()) {
            log.info("Cloning {} into {}", location, clonePath);
            result = runClone(PARTIAL_CLONE_ARGUMENTS, location, stagingPath);
            if (!result.isSuccessful()) {
                log.info("Partial clone of {} failed, retrying as a full clone", location);
                result = runClone(FULL_CLONE_ARGUMENTS, location, stagingPath);
            }
            if (result.isSuccessful()) {
                move(stagingPath, clonePath);
                lastFetch.put(reference.getWorkspaceKey(), Instant.now());
                return clonePath;
            }
            log.info("Unable to clone {}: {}", location, result.getOutput());
        }

        deleteRecursively(stagingPath);
        throw new IllegalStateException(
                "Unable to clone " + reference.getLocation() + ". Tried "
                        + reference.cloneLocations() + ". Check the URL and that this machine has "
                        + "credentials for it."
                        + System.lineSeparator()
                        + (result == null ? "" : result.getOutput()));
    }

    private GitCommandRunner.GitResult runClone(
            final List<String> cloneArguments,
            final String location,
            final Path stagingPath) {
        deleteRecursively(stagingPath);
        final String[] arguments = Stream.concat(
                        cloneArguments.stream(),
                        Stream.of(location, stagingPath.toString()))
                .toArray(String[]::new);
        return commandRunner.tryRun(
                workspaceRoot, GitCommandRunner.NETWORK_TIMEOUT_SECONDS, arguments);
    }

    private void fetchIfStale(
            final GitRepositoryReference reference, final Path clonePath, final boolean forceFetch) {
        final Instant fetchedAt = lastFetch.get(reference.getWorkspaceKey());
        final boolean stale =
                fetchedAt == null || fetchedAt.plus(FETCH_INTERVAL).isBefore(Instant.now());
        if (forceFetch || stale) {
            fetchNow(clonePath);
            lastFetch.put(reference.getWorkspaceKey(), Instant.now());
        }
    }

    private String fetchNow(final Path clonePath) {
        return commandRunner.run(
                clonePath,
                GitCommandRunner.NETWORK_TIMEOUT_SECONDS,
                "Already up to date.",
                "fetch", "--prune", "--no-tags", "origin");
    }

    private Path verifyGitDirectory(final Path path) {
        final GitCommandRunner.GitResult result = commandRunner.tryRun(
                path,
                GitCommandRunner.COMMAND_TIMEOUT_SECONDS,
                "rev-parse", "--git-dir");
        if (!result.isSuccessful()) {
            throw new IllegalArgumentException("Not a Git repository: " + path);
        }
        return path;
    }

    private void createDirectories(final Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to create the Git workspace directory " + path + ": " + e.getMessage(),
                    e);
        }
    }

    private void move(final Path source, final Path target) {
        try {
            Files.move(source, target);
        } catch (IOException e) {
            deleteRecursively(source);
            throw new IllegalStateException(
                    "Unable to publish the clone at " + target + ": " + e.getMessage(), e);
        }
    }

    private void deleteRecursively(final Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(path)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    log.warn("Unable to delete {}: {}", entry, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Unable to clean up {}: {}", path, e.getMessage());
        }
    }

    private static Path defaultWorkspaceRoot() {
        final String configured = System.getProperty(
                "git.workspace.root",
                System.getenv().getOrDefault(
                        "GIT_WORKSPACE_ROOT",
                        System.getProperty("user.home") + "/.langchain-mcp/repositories"));
        return Path.of(configured);
    }
}
