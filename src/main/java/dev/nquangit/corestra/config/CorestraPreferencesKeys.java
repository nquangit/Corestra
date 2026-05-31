package dev.nquangit.corestra.config;

/**
 * Defines non-project UI preference keys stored in Burp preferences.
 */
public final class CorestraPreferencesKeys {
    /**
     * Stores the identifier of the last flow selected in the UI.
     */
    public static final String LAST_SELECTED_FLOW_ID = "corestra.ui.lastSelectedFlowId";

    private CorestraPreferencesKeys() {
        throw new IllegalStateException("Utility class");
    }
}
