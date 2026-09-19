package com.langchain.central.service.tool;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.service.tool.git.GitCommandRunner;
import com.langchain.central.service.tool.git.GitWorkspace;
import com.langchain.central.service.tool.git.MergeRequestReference;
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
import java.util.regex.Matcher;
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


    private static final String REPOSITORY_DESCRIPTION =
            "Repository to act on: a clone URL, the project URL copied from the browser, or the "
                    + "path of a local clone";

    private static final String MERGE_REQUEST_DESCRIPTION =
            "Merge request link, such as "
                    + "https://gitlab.example.com/group/project/-/merge_requests/727";

    private static final String REVIEW_CONTEXT_DESCRIPTION =
            "What the review must focus on, such as clean code, design patterns, function names, "
                    + "field names and constants";

    /** Local ref the merge request head is fetched into, kept out of the branch namespace. */
    private static final String MERGE_REQUEST_REF_PREFIX = "refs/mcp/merge-requests/";

    /** Remote ref layouts that publish a merge request head: GitLab first, then GitHub. */
    private static final List<String> MERGE_REQUEST_REMOTE_REFS =
            List.of("refs/merge-requests/%s/head", "refs/pull/%s/head");

    private static final List<String> FALLBACK_TARGET_BRANCHES =
            List.of("origin/main", "origin/master", "origin/develop");

    private static final String NEW_LINE = System.lineSeparator();

    private static final int DEFAULT_MAX_REVIEW_CHARACTERS = 12_000;

    /** How many excluded files are named before the reviewer is pointed at the full file list. */
    private static final int MAX_REPORTED_EXCLUSIONS = 20;

    /**
     * Character budget for the patch handed to a reviewing model. A large merge request produces
     * megabytes of diff, which exceeds the context of a local model and makes a review appear to
     * hang, so the patch is bounded and the remainder is named rather than silently cut.
     *
     * <p>Tuned with the {@code review.max.characters} system property or the
     * {@code REVIEW_MAX_CHARACTERS} environment variable, because the usable size is decided by
     * the context window of whichever model the reviewer runs.
     */
    private static final int MAX_REVIEW_CHARACTERS = resolveMaxReviewCharacters();

    /** Start of each file block of a patch, which {@code git diff} writes as {@code diff --git}. */
    private static final String FILE_PATCH_BOUNDARY = "(?m)^(?=diff --git )";

    private static final Pattern PATCH_FILE_NAME =
            Pattern.compile("^diff --git a/(?<path>.+?) b/");

    /**
     * Tells the reviewing model to anchor every comment to a file and a line, so the reviewer can
     * open the exact place instead of rereading the whole patch.
     */
    private static final String REVIEW_REPORT_INSTRUCTIONS =
            "This result is complete. Do not call any further tool. Write the review now, using "
                    + "only the changed lines above and the review context. For every finding "
                    + "write one line as: <file path>:<line number> - <finding> - <suggested "
                    + "fix>. Take the line number from the nearest @@ hunk header of that file. "
                    + "Group the findings by file and say \"No findings.\" for a file that is "
                    + "clean.";

    private final Path repositoryPath;
    private final GitWorkspace workspace;
    private final GitCommandRunner commandRunner;


    @Inject
    public GitToolService(final GitWorkspace workspace, final GitCommandRunner commandRunner) {
        this(workspace, commandRunner, resolveRepositoryPath());
    }


    public GitToolService(
            final GitWorkspace workspace,
            final GitCommandRunner commandRunner,
            final Path repositoryPath) {
        this.repositoryPath = repositoryPath.toAbsolutePath().normalize();
        this.workspace = workspace;
        this.commandRunner = commandRunner;
    }

    /*
    Get the current branch and working tree status of a repository= https://github.com/AnkushNakaskar/LangChainDemo/tree/feature/git-assistance
     */
    @Tool("Get the current branch and working tree status of a repository")
    public String getGitStatus(@P(REPOSITORY_DESCRIPTION) final String repository) {
        Path localRepositoryPath =  workspace.checkout(repository,true);
        return runGit(localRepositoryPath,"Working tree is clean.", "status", "--short", "--branch");

    }

//    @Tool("Get the current Git branch and working tree status for code review")
//    public String getGitStatus() {
//        return runGit("Working tree is clean.", "status", "--short", "--branch");
//    }

    /*
    Get recent Git commits to understand  history of a repository= https://github.com/AnkushNakaskar/LangChainDemo/tree/feature/git-assistance
     */
    @Tool("Get recent Git commits to understand  history of a repository mentioned")
    public String getRecentCommits(@P(REPOSITORY_DESCRIPTION) final String repository
            ) {
        Path localRepositoryPath =  workspace.checkout(repository,true);
        return runGit(localRepositoryPath,
                "No commits found.",
                "log",
                "--max-count=" + 5,
                "--date=iso-strict",
                "--pretty=format:%h%x09%ad%x09%an%x09%s");
    }

//    @Tool("Get recent Git commits to understand repository history")
//    public String getRecentCommits(
//            @P("Number of commits to return, from 1 to 100") final String maxCount) {
//        return runGit(
//                "No commits found.",
//                "log",
//                "--max-count=" + maxCount,
//                "--date=iso-strict",
//                "--pretty=format:%h%x09%ad%x09%an%x09%s");
//    }

    /*
    Get unstaged changes in the Git working tree as a patch for the repository=https://github.com/AnkushNakaskar/LangChainDemo/tree/feature/git-assistance
     */
    @Tool("Get unstaged changes in the Git working tree as a patch for the repository mentioned")
    public String getWorkingTreeDiff(@P(REPOSITORY_DESCRIPTION) final String repository) {
        Path localRepositoryPath =  workspace.checkout(repository,true);
        return runGit(localRepositoryPath,"No unstaged changes.", "diff", "--no-ext-diff", "--unified=3", "--");
    }


    /*
    Pls use the tool and invoke and Get staged Git changes as a patch for repository: https://github.com/AnkushNakaskar/LangChainDemo/tree/feature/git-assistance
     */
    @Tool("Get staged Git changes as a patch for repository mentioned")
    public String getStagedDiff(@P(REPOSITORY_DESCRIPTION) final String repository) {
        Path localRepositoryPath =  workspace.checkout(repository,true);
        return runGit(localRepositoryPath,
                "No staged changes.",
                "diff",
                "--cached",
                "--no-ext-diff",
                "--unified=3",
                "--");
    }

    /*
    Please review this MR : https://gitlab.phonepe.com/central-platforms/disputes/stratos/-/merge_requests/727 With context like : Pls review with clean code, design pattern, function names, fields name and constant.

     */
    @Tool("Review a merge request: fetch the merge request, compare it with its target branch, "
            + "and return its description and the changed lines of every file, to be reviewed "
            + "against the context supplied by the reviewer")
    public String reviewMergeRequest(
            @P(MERGE_REQUEST_DESCRIPTION) final String mergeRequest,
            @P(REVIEW_CONTEXT_DESCRIPTION) final String context) {
        final MergeRequestReference reference = MergeRequestReference.parse(mergeRequest);
        final String repository = reference.resolveRepository(null);
        final long startedAt = System.currentTimeMillis();
        log.info("Reviewing merge request {} of {}", reference.getIid(), repository);

        // The merge request head and its target branch are fetched explicitly below, so a fetch of
        // every other branch here would only repeat that work against a large repository.
        final Path localRepositoryPath = workspace.checkout(repository, false);
        final String headRevision = fetchMergeRequestHead(localRepositoryPath, reference.getIid());
        final String targetRevision = resolveTargetBranch(localRepositoryPath);
        final String baseRevision = commandRunner.run(
                localRepositoryPath, targetRevision, "merge-base", targetRevision, headRevision);
        log.info("Merge request {} is ready to review after {} ms",
                reference.getIid(), System.currentTimeMillis() - startedAt);

        //TODO : This need to be refactored correctly
        String response =  String.join(NEW_LINE + NEW_LINE,
                "# Merge request " + reference.getIid() + " of " + repository,
                "Target branch: " + targetRevision + NEW_LINE + "Merge base: " + baseRevision,
                section("Review context", context),
                section("Merge request description", describe(
                        localRepositoryPath, baseRevision, headRevision)),
//                section("Changed files", runGit(
//                        localRepositoryPath,
//                        "No files changed.",
//                        "diff", "--name-status", baseRevision, headRevision, "--")),
//                section("Changes per file and line", changesPerFile(
//                        localRepositoryPath, baseRevision, headRevision)),
                section("How to report", REVIEW_REPORT_INSTRUCTIONS));
        return response;
    }

    /**
     * The patch split into one block per file, kept within a budget a reviewing model can actually
     * read. A large merge request produces megabytes of diff, which no local model can hold, so the
     * files that did not fit are named instead of being dropped silently.
     */
    private String changesPerFile(
            final Path localRepositoryPath, final String baseRevision, final String headRevision) {
        final String patch = runGit(
                localRepositoryPath,
                "",
                "diff", "--no-ext-diff", "--unified=3", baseRevision, headRevision, "--");
        if (patch.isBlank()) {
            return "No changes.";
        }

        final StringBuilder included = new StringBuilder();
        final List<String> excludedFiles = new ArrayList<>();
        for (final String filePatch : patch.split(FILE_PATCH_BOUNDARY)) {
            if (filePatch.isBlank()) {
                continue;
            }
            if (included.length() + filePatch.length() <= MAX_REVIEW_CHARACTERS) {
                included.append(filePatch);
            } else {
                excludedFiles.add(fileNameOf(filePatch));
            }
        }

        if (excludedFiles.isEmpty()) {
            return included.toString();
        }
        return included
                + NEW_LINE
                + "[" + excludedFiles.size() + " file(s) did not fit in this review and are not "
                + "shown, starting with: " + String.join(", ", namesToReport(excludedFiles))
                + ". Review only what is shown above and state at the end that these files were "
                + "not reviewed. Do not call any further tool for them.]";
    }

    /** Names enough of the excluded files to act on without repeating the whole file list. */
    private List<String> namesToReport(final List<String> excludedFiles) {
        return excludedFiles.size() <= MAX_REPORTED_EXCLUSIONS
               ? excludedFiles
               : excludedFiles.subList(0, MAX_REPORTED_EXCLUSIONS);
    }

    /** The path named by a single file block of a patch, used to report what was left out. */
    private String fileNameOf(final String filePatch) {
        final Matcher matcher = PATCH_FILE_NAME.matcher(filePatch);
        return matcher.find() ? matcher.group("path") : "unknown file";
    }

    /**
     * Fetches the merge request head into a local ref. The head is published by the host rather
     * than pushed as a branch, so it is not present in a plain clone.
     */
    private String fetchMergeRequestHead(final Path localRepositoryPath, final String iid) {
        final String localRef = MERGE_REQUEST_REF_PREFIX + iid;
        for (final String remoteRefLayout : MERGE_REQUEST_REMOTE_REFS) {
            final String remoteRef = String.format(remoteRefLayout, iid);
            final GitCommandRunner.GitResult result = commandRunner.tryRun(
                    localRepositoryPath,
                    GitCommandRunner.NETWORK_TIMEOUT_SECONDS,
                    "fetch", "--no-tags", "origin", "+" + remoteRef + ":" + localRef);
            if (result.isSuccessful()) {
                return localRef;
            }
            log.info("Merge request ref {} is not available: {}", remoteRef, result.getOutput());
        }
        throw new IllegalStateException(
                "Unable to fetch merge request " + iid + " from origin. Check the merge request "
                        + "number and that this machine has access to the repository.");
    }

    /** The branch the merge request is raised against, which the diff is taken from. */
    private String resolveTargetBranch(final Path localRepositoryPath) {
        final GitCommandRunner.GitResult remoteHead = commandRunner.tryRun(
                localRepositoryPath,
                GitCommandRunner.COMMAND_TIMEOUT_SECONDS,
                "rev-parse", "--abbrev-ref", "origin/HEAD");
        if (remoteHead.isSuccessful() && !remoteHead.getOutput().isBlank()) {
            return remoteHead.getOutput();
        }
        for (final String candidate : FALLBACK_TARGET_BRANCHES) {
            final GitCommandRunner.GitResult exists = commandRunner.tryRun(
                    localRepositoryPath,
                    GitCommandRunner.COMMAND_TIMEOUT_SECONDS,
                    "rev-parse", "--verify", "--quiet", candidate);
            if (exists.isSuccessful()) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to determine the target branch of the repository. Expected one of "
                        + FALLBACK_TARGET_BRANCHES + " to exist.");
    }

    /** Commit messages of the merge request, which carry the description of the change. */
    private String describe(
            final Path localRepositoryPath, final String baseRevision, final String headRevision) {
        return runGit(
                localRepositoryPath,
                "The merge request has no commits.",
                "log",
                "--date=iso-strict",
                "--pretty=format:%h%x09%ad%x09%an%x09%s%n%b",
                baseRevision + ".." + headRevision);
    }

    private String section(final String title, final String body) {
        return "## " + title + NEW_LINE + (body == null || body.isBlank() ? "Not provided." : body);
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



    private String runGit(final Path localRepositoryPath, final String emptyResult, final String... arguments) {
        log.info("Invoking git with custom path .......");
        if (!Files.isDirectory(localRepositoryPath)) {
            throw new IllegalStateException(
                    "Git repository directory does not exist: " + localRepositoryPath);
        }

        final List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(List.of(arguments));
        log.info("Running Git tool command in {}: {}", localRepositoryPath, command);

        final ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(localRepositoryPath.toFile())
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

    private static int resolveMaxReviewCharacters() {
        final String configured = System.getProperty(
                "review.max.characters",
                System.getenv().getOrDefault(
                        "REVIEW_MAX_CHARACTERS",
                        String.valueOf(DEFAULT_MAX_REVIEW_CHARACTERS)));
        try {
            final int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : DEFAULT_MAX_REVIEW_CHARACTERS;
        } catch (NumberFormatException e) {
            log.warn("Ignoring review character budget {}, which is not a number", configured);
            return DEFAULT_MAX_REVIEW_CHARACTERS;
        }
    }

    private static Path resolveRepositoryPath() {        final String configuredPath = System.getProperty(
                "git.repository.path",
                System.getenv().getOrDefault("GIT_REPOSITORY_PATH", System.getProperty("user.dir")));
        return Path.of(configuredPath);
    }
}
