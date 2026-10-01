package dev.nquangit.corestra.session;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically refreshes session profiles that opt into scheduler-based authentication.
 */
public final class SessionSchedulerService implements AutoCloseable {
    private static final long STARTUP_DELAY_SECONDS = 5L;
    private static final long PERIODIC_INITIAL_DELAY_SECONDS = 30L;
    private static final long PERIODIC_DELAY_SECONDS = 30L;

    private final SessionManager sessionManager;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a scheduler service.
     *
     * @param sessionManager session manager
     * @param clock runtime clock
     */
    public SessionSchedulerService(SessionManager sessionManager, Clock clock) {
        this(sessionManager, clock, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "corestra-session-scheduler");
            thread.setDaemon(true);
            return thread;
        }));
    }

    SessionSchedulerService(SessionManager sessionManager, Clock clock, ScheduledExecutorService executor) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Starts periodic refresh checks.
     */
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(this::refreshStartupProfilesSafely, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(
                this::refreshDueProfilesSafely,
                PERIODIC_INITIAL_DELAY_SECONDS,
                PERIODIC_DELAY_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Stops scheduler execution.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
    }

    private void refreshStartupProfilesSafely() {
        if (closed.get()) {
            return;
        }
        try {
            for (SessionProfile profile : sessionManager.profiles()) {
                if (closed.get()) {
                    return;
                }
                if (profile.enabled()
                        && profile.scheduler().enabled()
                        && profile.scheduler().runAtStartup()
                        && sessionManager.hasAuthentication(profile)) {
                    refreshProfileSafely(profile.id());
                }
            }
        } catch (RuntimeException ignored) {
            // Scheduler failures must never affect Burp traffic.
        }
    }

    private void refreshDueProfilesSafely() {
        if (closed.get()) {
            return;
        }
        try {
            long now = clock.millis();
            for (SessionProfile profile : sessionManager.profiles()) {
                if (closed.get()) {
                    return;
                }
                if (!profile.enabled()
                        || !profile.scheduler().enabled()
                        || !sessionManager.hasAuthentication(profile)) {
                    continue;
                }
                long last = profile.state().lastAuthenticatedAtEpochMillis();
                long intervalMillis = profile.scheduler().intervalSeconds() * 1000L;
                if (last == 0L || last + intervalMillis <= now || profile.state().missingOrExpired(now)) {
                    try {
                        sessionManager.refreshScheduledProfile(profile.id());
                    } catch (RuntimeException ignored) {
                        // A concurrent refresh or one broken profile must not stop other profiles.
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Scheduler failures must never affect Burp traffic.
        }
    }

    private void refreshProfileSafely(String profileId) {
        try {
            sessionManager.forceAuthenticate(profileId);
        } catch (RuntimeException ignored) {
            // One broken scheduled auth flow must not stop other scheduled profiles.
        }
    }
}
