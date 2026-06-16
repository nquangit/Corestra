package dev.nquangit.corestra.access.postcheck;

/**
 * Describes which auth profile should be used for a post-check request.
 */
public enum PostCheckAuthBinding {
    TARGET_ACTOR,
    DATA_OWNER,
    FIXED_PROFILE
}
