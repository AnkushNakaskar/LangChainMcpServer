package com.langchain.central.service.tool.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * A merge request identified by the link a reviewer was given.
 *
 * <p>The link already names both the repository and the change, so parsing it here means a reviewer
 * only has to paste one value instead of restating the repository separately.
 */
@Value
public class MergeRequestReference {

    /** GitLab writes {@code /-/merge_requests/<iid>}, GitHub {@code /pull/<number>}. */
    private static final Pattern MERGE_REQUEST_URL = Pattern.compile(
            "^(?<repository>https?://[^\\s]+?)/(?:-/)?(?:merge_requests|pull|pulls)/(?<iid>\\d+)"
                    + "(?:[/?#].*)?$");

    private static final Pattern MERGE_REQUEST_NUMBER = Pattern.compile("^#?(?<iid>\\d+)$");

    /** Repository the merge request belongs to, or {@code null} when only a number was given. */
    String repositoryUrl;

    /** Merge request number within its project. */
    String iid;

    /**
     * Parses a merge request link, or a bare number when the repository is supplied separately.
     *
     * @param mergeRequest merge request URL, or its number
     */
    public static MergeRequestReference parse(final String mergeRequest) {
        if (mergeRequest == null || mergeRequest.isBlank()) {
            throw new IllegalArgumentException("Merge request URL or number is required");
        }
        final String trimmed = mergeRequest.trim();

        final Matcher url = MERGE_REQUEST_URL.matcher(trimmed);
        if (url.matches()) {
            return new MergeRequestReference(url.group("repository"), url.group("iid"));
        }

        final Matcher number = MERGE_REQUEST_NUMBER.matcher(trimmed);
        if (number.matches()) {
            return new MergeRequestReference(null, number.group("iid"));
        }

        throw new IllegalArgumentException(
                "Unable to read a merge request number from: " + mergeRequest);
    }

    /**
     * Resolves which repository to review, preferring an explicitly supplied one so that a fork or
     * a mirror can override the host named in the link.
     *
     * @param explicitRepository repository passed by the caller, may be blank
     */
    public String resolveRepository(final String explicitRepository) {
        if (explicitRepository != null && !explicitRepository.isBlank()) {
            return explicitRepository.trim();
        }
        if (repositoryUrl == null) {
            throw new IllegalArgumentException(
                    "Merge request " + iid + " was given as a number only. Pass the repository URL "
                            + "as well, or pass the full merge request link.");
        }
        return repositoryUrl;
    }
}
