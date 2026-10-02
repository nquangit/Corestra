package dev.nquangit.corestra.session;

import java.util.List;

/** Immutable runtime-only scheduler summary; contains no auth values or HTTP evidence. */
public record SessionSchedulerSnapshot(long capturedAtEpochMillis, boolean started, boolean closed,
                                       int activeWorkers, int workerLimit, int queuedJobs,
                                       List<SessionScheduledJob> jobs) {
    /** Copies the job list so UI readers cannot mutate scheduler state. */
    public SessionSchedulerSnapshot {
        jobs = List.copyOf(jobs);
    }
}
