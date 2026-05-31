package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Preferences;
import dev.nquangit.corestra.config.CorestraPreferencesKeys;

import java.util.Objects;

/**
 * Provides typed access to non-project UI preferences stored in Burp preferences.
 */
public final class UiPreferencesRepository {
    private final Preferences preferences;

    /**
     * Creates a preferences repository backed by Burp extension preferences.
     *
     * @param preferences the Montoya preferences facade
     */
    public UiPreferencesRepository(Preferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    /**
     * Returns the last flow identifier selected in the UI.
     *
     * @return the last selected flow identifier, or {@code null} when none is stored
     */
    public String lastSelectedFlowId() {
        String flowId = preferences.getString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID);
        if (flowId == null) {
            return null;
        }

        if (flowId.isBlank()) {
            clearLastSelectedFlowId();
            return null;
        }

        return flowId;
    }

    /**
     * Stores the last flow identifier selected in the UI.
     *
     * @param flowId the selected flow identifier, or {@code null} / blank to clear the stored value
     */
    public void setLastSelectedFlowId(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            clearLastSelectedFlowId();
            return;
        }

        preferences.setString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID, flowId);
    }

    /**
     * Removes the stored last selected flow identifier.
     */
    public void clearLastSelectedFlowId() {
        preferences.deleteString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID);
    }
}
