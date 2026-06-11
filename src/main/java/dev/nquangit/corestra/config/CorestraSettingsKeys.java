package dev.nquangit.corestra.config;

/**
 * Defines project-scoped persistence keys stored in Burp extension data.
 */
public final class CorestraSettingsKeys {
    /**
     * Stores the serialized flow repository document in Burp project data.
     */
    public static final String FLOW_STORAGE_DOCUMENT = "corestra.flows.document";

    /**
     * Stores the last invalid raw flow repository payload quarantined during recovery.
     */
    public static final String FLOW_STORAGE_DOCUMENT_QUARANTINE = "corestra.flows.document.quarantine";

    /**
     * Stores serialized project metadata (name, owner, timestamps, etc.) in Burp project data.
     */
    public static final String PROJECT_METADATA = "corestra.project.metadata";

    /**
     * Stores persisted Access Testing configuration in Burp project data.
     */
    public static final String ACCESS_WORKSPACE_DOCUMENT = "corestra.access.workspace.document";

    /**
     * Stores the last invalid raw Access Testing configuration payload quarantined during recovery.
     */
    public static final String ACCESS_WORKSPACE_DOCUMENT_QUARANTINE = "corestra.access.workspace.document.quarantine";

    private CorestraSettingsKeys() {
        throw new IllegalStateException("Utility class");
    }
}
