package dev.nquangit.corestra.access.teardown;

/**
 * Selects which auth profile is used when a teardown flow executes.
 */
public enum TeardownAuthBinding {
    /**
     * Use the owner auth profile from the selected data profile.
     */
    DATA_OWNER,

    /**
     * Use the target actor auth profile from the access test case.
     */
    TARGET_ACTOR,

    /**
     * Use the fixed auth profile configured on the teardown.
     */
    FIXED_PROFILE
}
