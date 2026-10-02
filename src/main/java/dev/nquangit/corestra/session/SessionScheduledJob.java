package dev.nquangit.corestra.session;

/** Immutable, secret-free view of one Session Handler's scheduled maintenance. */
public record SessionScheduledJob(
        String profileId, String profileName, String actorName, String mode, String flowId,
        Status status, long intervalSeconds, long lastStartedAtEpochMillis,
        long lastCompletedAtEpochMillis, long lastDurationMillis, long nextRunAtEpochMillis,
        String lastOutcome) {
    /** Current scheduling phase, separate from the last attempt's outcome. */
    public enum Status {
        NOT_STARTED, STOPPED, DISABLED, NO_AUTH, WAITING, WAITING_WORKER, QUEUED, RUNNING, BACKOFF
    }
}
