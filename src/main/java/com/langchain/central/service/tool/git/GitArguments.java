package com.langchain.central.service.tool.git;

import java.util.regex.Pattern;

/**
 * Validation for the values a review agent passes into the Git tools.
 *
 * <p>Arguments reach Git as separate process arguments rather than through a shell, so the checks
 * here exist to stop a value being read as a Git option or as a path outside the repository, and to
 * turn a malformed argument into a message the agent can act on.
 */
public final class GitArguments {

    private static final Pattern REVISION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@~^{}-]*");

    private static final Pattern UNSAFE_PATH_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");

    public static final int MAX_COMMIT_COUNT = 100;

    private GitArguments() {
    }

    /** True when an optional argument was actually supplied. */
    public static boolean isPresent(final String value) {
        return value != null && !value.isBlank();
    }

    public static String requireRevision(final String revision) {
        if (revision == null || !REVISION.matcher(revision.trim()).matches()) {
            throw new IllegalArgumentException(
                    "Revision must be a branch, tag, or commit SHA without command options, but "
                            + "was: " + revision);
        }
        return revision.trim();
    }

    /** Searches default to {@code HEAD} so an omitted revision does not fail the tool call. */
    public static String requireSearchRevision(final String revision) {
        return isPresent(revision) ? requireRevision(revision) : "HEAD";
    }

    /**
     * Rejects absolute paths, parent directory traversal and anything Git would read as an option,
     * so a tool call cannot reach outside the repository. Spaces and unicode are allowed, because
     * real repositories contain such file names.
     */
    public static String requirePath(final String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path is required");
        }
        final String trimmed = filePath.trim();
        if (trimmed.startsWith("-")) {
            throw new IllegalArgumentException(
                    "File path must not start with '-', which Git would read as an option: "
                            + filePath);
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("~")) {
            throw new IllegalArgumentException(
                    "File path must be repository relative, not absolute: " + filePath);
        }
        if (UNSAFE_PATH_CHARACTERS.matcher(trimmed).find()) {
            throw new IllegalArgumentException(
                    "File path must not contain control characters: " + filePath);
        }
        if (hasParentTraversal(trimmed)) {
            throw new IllegalArgumentException(
                    "File path must not contain a '..' segment: " + filePath);
        }
        return trimmed;
    }

    public static String requirePattern(final String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Search pattern is required");
        }
        return pattern;
    }

    public static int requireCount(final int maxCount) {
        if (maxCount < 1 || maxCount > MAX_COMMIT_COUNT) {
            throw new IllegalArgumentException(
                    "Count must be from 1 to " + MAX_COMMIT_COUNT + ", but was: " + maxCount);
        }
        return maxCount;
    }

    public static int requireLineNumber(final int line) {
        if (line < 1) {
            throw new IllegalArgumentException(
                    "Line number must be a positive integer, but was: " + line);
        }
        return line;
    }

    public static String stripTrailingSlash(final String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static boolean hasParentTraversal(final String path) {
        for (final String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
