package dev.nquangit.corestra.http.retry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real-time {@link RetryWaiter} that releases every waiting Burp thread as soon as it is closed. */
public final class ClosableRetryWaiter implements RetryWaiter, AutoCloseable {
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public boolean await(long millis) {
        if (millis <= 0) return closed.getCount() > 0;
        try {
            return !closed.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        closed.countDown();
    }
}
