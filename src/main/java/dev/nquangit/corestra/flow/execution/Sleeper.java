package dev.nquangit.corestra.flow.execution;

/**
 * Sleeps for flow control actions.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Sleeps for the supplied duration.
     *
     * @param millis duration in milliseconds
     * @throws InterruptedException when interrupted while sleeping
     */
    void sleep(long millis) throws InterruptedException;
}
