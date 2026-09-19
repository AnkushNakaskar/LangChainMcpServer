package com.langchain.central.service.tool.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * A repository the review tools may be pointed at, given either as a remote URL or as a path to a
 * clone that already exists on disk.
 *
 * <p>Review requests arrive as links, so the tools must accept a link rather than assume whichever
 * repository the server process happens to be running in. A remote reference is cloned once into a
 * workspace directory and reused afterwards; a local reference is used where it already is.
 */
@Value
public class GitRepositoryReference {

    /** Host placeholder used when a clone URL carries no host, such as an on-disk bare mirror. */
    private static final String UNKNOWN_HOST = "local";

    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[\\s\\p{Cntrl}]");

    /** {@code ext::} and {@code fd::} remotes can run commands on clone, so they are refused. */
    private static final Pattern REJECTED_TRANSPORT =
            Pattern.compile("^(ext|fd)::", Pattern.CASE_INSENSITIVE);

    private static final String FILE_URL_PREFIX = "file://";

    /** A {@code file://} URL is cloned from, so it needs no host and keeps its path verbatim. */
    private static final Pattern FILE_URL = Pattern.compile("^file://(?<path>/.+)$");

    private static final Pattern SCP_STYLE_URL =
            Pattern.compile("^(?<user>[A-Za-z0-9._-]+)@(?<host>[A-Za-z0-9._-]+):(?<path>[^:].*)$");

    private static final Pattern SCHEME_URL =
            Pattern.compile("^(?<scheme>https?|ssh|git)://(?:[^/@]+@)?(?<host>[^/:]+)"
                    + "(?::\\d+)?/(?<path>.+)$");

    /** Web suffixes a browser adds to a project URL, removed to recover the clone URL. */
    private static final Pattern WEB_SUFFIX = Pattern.compile(
            "/(?:-/)?(?:merge_requests|pull|pulls|tree|blob|commits?|compare|issues)(?:/.*)?$");

    /** Clone URL when remote, or the absolute directory when local. */
    String location;

    /**
     * Equivalent SSH URL tried when the primary one is refused, or {@code null} when there is no
     * equivalent. Review links are copied from a browser and so are almost always {@code https},
     * while a developer machine is usually set up with an SSH key rather than an HTTP token.
     */
    String sshFallbackLocation;

    /** Absolute path of an existing clone, or {@code null} when the repository is remote. */
    Path localPath;

    /** Stable directory name used to cache a remote clone. */
    String workspaceKey;

    public boolean isLocal() {
        return localPath != null;
    }

    /** Clone URLs to try, in order of preference. */
    public List<String> cloneLocations() {
        return sshFallbackLocation == null
               ? List.of(location)
               : List.of(location, sshFallbackLocation);
    }

    /**
     * Parses a repository reference supplied by a caller.
     *
     * @param repository clone URL, project web URL, or path to an existing clone
     */
    public static GitRepositoryReference parse(final String repository) {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository is required: pass a clone URL, a project URL, or the path of a "
                            + "local clone");
        }
        final String trimmed = repository.trim();
        if (UNSAFE_CHARACTERS.matcher(trimmed).find()) {
            throw new IllegalArgumentException(
                    "Repository must not contain whitespace or control characters: " + repository);
        }
        if (trimmed.startsWith("-")) {
            throw new IllegalArgumentException(
                    "Repository must not start with '-', which Git would read as an option: "
                            + repository);
        }
        if (REJECTED_TRANSPORT.matcher(trimmed).find()) {
            throw new IllegalArgumentException(
                    "Repository transport is not allowed: " + repository);
        }
        return looksLikeLocalPath(trimmed) ? local(trimmed) : remote(trimmed);
    }

    private static boolean looksLikeLocalPath(final String repository) {
        return repository.startsWith("/")
                || repository.startsWith("./")
                || repository.startsWith("../")
                || repository.startsWith("~/");
    }

    private static GitRepositoryReference local(final String repository) {
        final Path path = Path.of(repository.startsWith("~/")
                                  ? System.getProperty("user.home") + repository.substring(1)
                                  : repository)
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Local repository directory does not exist: " + path);
        }
        return new GitRepositoryReference(
                path.toString(), null, path, workspaceKey(path.toString()));
    }

    private static GitRepositoryReference remote(final String repository) {
        final String cloneUrl = toCloneUrl(repository);
        return new GitRepositoryReference(
                cloneUrl, toSshUrl(cloneUrl), null, workspaceKey(cloneUrl));
    }

    /**
     * Rewrites an {@code https} clone URL as its SSH equivalent, used only as a fallback when the
     * HTTP clone is refused.
     */
    private static String toSshUrl(final String cloneUrl) {
        final Matcher matcher = SCHEME_URL.matcher(cloneUrl);
        if (!matcher.matches() || !matcher.group("scheme").startsWith("http")) {
            return null;
        }
        return "git@" + matcher.group("host") + ":" + matcher.group("path");
    }

    /**
     * Converts a link as it appears in a browser into the URL Git can clone, so that a reviewer can
     * paste the same link they were sent.
     */
    private static String toCloneUrl(final String repository) {
        String url = stripTrailingSlash(repository);
        if (url.startsWith(FILE_URL_PREFIX)) {
            if (!FILE_URL.matcher(url).matches()) {
                throw new IllegalArgumentException(
                        "A file:// repository must use an absolute path: " + repository);
            }
            return url;
        }
        url = WEB_SUFFIX.matcher(url).replaceFirst("");
        url = stripTrailingSlash(url);
        if (!isRecognisedUrl(url)) {
            throw new IllegalArgumentException(
                    "Repository must be an https, ssh or git URL, or the path of a local clone: "
                            + repository);
        }
        return url.endsWith(".git") ? url : url + ".git";
    }

    private static boolean isRecognisedUrl(final String url) {
        return SCHEME_URL.matcher(url).matches()
                || SCP_STYLE_URL.matcher(url).matches()
                || FILE_URL.matcher(url).matches();
    }

    private static String stripTrailingSlash(final String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Builds a readable but collision free directory name, so that two projects with the same short
     * name from different hosts do not share one clone.
     */
    private static String workspaceKey(final String location) {
        final String host = hostOf(location);
        final String name = location
                .replaceAll("\\.git$", "")
                .replaceAll(".*[/:]", "")
                .replaceAll("[^A-Za-z0-9._-]", "-");
        return (host + "-" + (name.isBlank() ? "repo" : name) + "-" + shortDigest(location))
                .toLowerCase(Locale.ROOT);
    }

    private static String hostOf(final String location) {
        final Matcher scheme = SCHEME_URL.matcher(location);
        if (scheme.matches()) {
            return scheme.group("host").replaceAll("[^A-Za-z0-9.-]", "-");
        }
        final Matcher scp = SCP_STYLE_URL.matcher(location);
        if (scp.matches()) {
            return scp.group("host").replaceAll("[^A-Za-z0-9.-]", "-");
        }
        return UNKNOWN_HOST;
    }

    private static String shortDigest(final String value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 5; index++) {
                hex.append(String.format("%02x", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
