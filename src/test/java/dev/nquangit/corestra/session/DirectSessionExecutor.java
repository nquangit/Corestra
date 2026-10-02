package dev.nquangit.corestra.session;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Runs scheduled maintenance synchronously for deterministic clock-based tests. */
final class DirectSessionExecutor extends AbstractExecutorService {
    private boolean shutdown;
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown(); return List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    @Override public void execute(Runnable task) {
        if (shutdown) throw new RejectedExecutionException("closed");
        task.run();
    }
}
