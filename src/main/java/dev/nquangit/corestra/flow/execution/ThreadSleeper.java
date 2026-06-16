package dev.nquangit.corestra.flow.execution;

/**
 * Sleeper backed by {@link Thread#sleep(long)}.
 */
public final class ThreadSleeper implements Sleeper {

    /**
     * Sleeps for the supplied duration.
     *
     * @param millis duration in milliseconds
     * @throws InterruptedException when interrupted while sleeping
     */
    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
