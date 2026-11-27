package dev.nquangit.corestra.http.retry;

/** Waits between retries; implementations stop early when the extension unloads. */
@FunctionalInterface
public interface RetryWaiter {

    /**
     * Waits for the given duration.
     *
     * @param millis duration in milliseconds
     * @return {@code true} when the full wait elapsed, {@code false} when it was aborted
     */
    boolean await(long millis);
}
