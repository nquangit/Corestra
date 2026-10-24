package dev.nquangit.corestra.config;

/**
 * Defines project-scoped persistence keys stored in Burp extension data.
 */
public final class CorestraSettingsKeys {
    /** Stores isolated, explicitly-run Manual Testing drafts. */
    public static final String MANUAL_FLOWS_DOCUMENT = "corestra.manual.flows.document";

    /** Stores the serialized Flowise repository document in Burp project data. */
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
     * Stores serialized project settings in Burp project data.
     */
    public static final String PROJECT_SETTINGS = "corestra.project.settings";

    /**
     * Stores persisted Access Testing configuration in Burp project data.
     */
    public static final String ACCESS_WORKSPACE_DOCUMENT = "corestra.access.workspace.document";

    /** Stores small auth-profile checkpoints independently of large access evidence. */
    public static final String ACCESS_AUTH_PROFILES_CHECKPOINT = "corestra.access.auth.profiles.checkpoint";

    /** Stores a malformed auth-profile checkpoint for recovery. */
    public static final String ACCESS_AUTH_PROFILES_CHECKPOINT_QUARANTINE = "corestra.access.auth.profiles.checkpoint.quarantine";

    /**
     * Stores the last invalid raw Access Testing configuration payload quarantined during recovery.
     */
    public static final String ACCESS_WORKSPACE_DOCUMENT_QUARANTINE = "corestra.access.workspace.document.quarantine";

    /**
     * Stores serialized Session Handler profiles in Burp project data.
     */
    public static final String SESSION_PROFILES_DOCUMENT = "corestra.session.profiles.document";

    /**
     * Stores the last invalid Session Handler payload quarantined during recovery.
     */
    public static final String SESSION_PROFILES_DOCUMENT_QUARANTINE = "corestra.session.profiles.document.quarantine";

    private CorestraSettingsKeys() {
        throw new IllegalStateException("Utility class");
    }
}
