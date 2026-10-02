package dev.nquangit.corestra.session;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically refreshes session profiles that opt into scheduler-based authentication.
 * Checks due times every second and dispatches network work to bounded workers, admitting
 * at most one queued or running maintenance per session profile and linked actor.
 */
public final class SessionSchedulerService implements AutoCloseable {
    private static final long STARTUP_DELAY_SECONDS = 5L;
    private static final long PERIODIC_INITIAL_DELAY_SECONDS = 5L;
    private static final long PERIODIC_PERIOD_SECONDS = 1L;
    private static final int MAX_CONCURRENT_MAINTENANCE = 4;
    private static final int MAX_QUEUED_MAINTENANCE = 64;

    private final SessionManager sessionManager;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final ExecutorService workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> actorsInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, RetryWindow> retryWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobRuntime> jobs = new ConcurrentHashMap<>();
    private volatile long startupAt;
    private volatile boolean startupCheckComplete;
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
        this(sessionManager, clock, executor, new ThreadPoolExecutor(
                MAX_CONCURRENT_MAINTENANCE, MAX_CONCURRENT_MAINTENANCE,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUED_MAINTENANCE), runnable -> {
                    Thread thread = new Thread(runnable, "corestra-session-maintenance");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    SessionSchedulerService(SessionManager sessionManager, Clock clock,
                            ScheduledExecutorService executor, ExecutorService workers) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    /**
     * Starts periodic refresh checks.
     */
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        startupAt = addMillis(clock.millis(), TimeUnit.SECONDS.toMillis(STARTUP_DELAY_SECONDS));
        executor.schedule(this::refreshStartupProfilesSafely, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(
                this::refreshDueProfilesSafely,
                PERIODIC_INITIAL_DELAY_SECONDS,
                PERIODIC_PERIOD_SECONDS,
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
        workers.shutdownNow();
        retryWindows.clear();
    }

    /** Returns current metadata and timings from RAM without executing authentication. */
    public SessionSchedulerSnapshot snapshot() {
        long now = clock.millis();
        List<SessionProfile> profiles = sessionManager.profiles();
        pruneJobs(profiles);
        List<SessionScheduledJob> views = profiles.stream().map(profile -> {
            JobRuntime job = jobs.getOrDefault(profile.id(), JobRuntime.empty());
            SessionManager.ScheduledAuthInfo info = sessionManager.scheduledAuthInfo(profile);
            SessionScheduledJob.Status status;
            long next = 0L;
            if (closed.get()) status = SessionScheduledJob.Status.STOPPED;
            else if (job.phase() == Phase.RUNNING) status = SessionScheduledJob.Status.RUNNING;
            else if (job.phase() == Phase.QUEUED) status = SessionScheduledJob.Status.QUEUED;
            else if (!profile.enabled() || !profile.scheduler().enabled()) status = SessionScheduledJob.Status.DISABLED;
            else if (!sessionManager.hasAuthentication(profile)) status = SessionScheduledJob.Status.NO_AUTH;
            else if (!started.get()) status = SessionScheduledJob.Status.NOT_STARTED;
            else {
                long last = info.state().lastAuthenticatedAtEpochMillis();
                next = last == 0L || info.state().missingOrExpired(now) ? now
                        : addMillis(last, TimeUnit.SECONDS.toMillis(profile.scheduler().intervalSeconds()));
                if (info.state().expiresAtEpochMillis() > 0L) {
                    next = Math.min(next, Math.max(now, info.state().expiresAtEpochMillis()));
                }
                if (!startupCheckComplete && profile.scheduler().runAtStartup()) next = startupAt;
                else if (!startupCheckComplete) next = Math.max(next, startupAt);
                RetryWindow retry = retryWindows.get(profile.id());
                long retryAt = retry != null && retry.matches(profile)
                        ? addMillis(retry.completedAt(), TimeUnit.SECONDS.toMillis(profile.scheduler().intervalSeconds())) : 0L;
                long blockedUntil = Math.max(retryAt, info.retryAt());
                next = Math.max(next, blockedUntil);
                if (blockedUntil > now) status = SessionScheduledJob.Status.BACKOFF;
                else if (next <= now) status = SessionScheduledJob.Status.WAITING_WORKER;
                else status = SessionScheduledJob.Status.WAITING;
            }
            return new SessionScheduledJob(profile.id(), profile.name(), info.actorName(), info.mode(), info.flowId(),
                    status, profile.scheduler().intervalSeconds(), job.startedAt(), job.completedAt(),
                    job.durationMillis(), next, job.outcome());
        }).toList();
        int running = (int) jobs.values().stream().filter(job -> job.phase() == Phase.RUNNING).count();
        int queued = closed.get() ? 0 : (int) jobs.values().stream().filter(job -> job.phase() == Phase.QUEUED).count();
        return new SessionSchedulerSnapshot(now, started.get(), closed.get(), running,
                MAX_CONCURRENT_MAINTENANCE, queued, views);
    }

    private void pruneJobs(List<SessionProfile> profiles) {
        Set<String> ids = profiles.stream().map(SessionProfile::id).collect(java.util.stream.Collectors.toSet());
        jobs.keySet().removeIf(id -> !ids.contains(id) && !inFlight.contains(id));
    }

    private static long addMillis(long base, long millis) {
        return base > Long.MAX_VALUE - millis ? Long.MAX_VALUE : base + millis;
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
                    dispatch(profile, true);
                }
            }
        } catch (RuntimeException ignored) {
            // Scheduler failures must never affect Burp traffic.
        } finally {
            startupCheckComplete = true;
        }
    }

    private void refreshDueProfilesSafely() {
        if (closed.get()) {
            return;
        }
        try {
            long now = clock.millis();
            List<SessionProfile> profiles = sessionManager.profiles();
            Set<String> currentIds = profiles.stream().map(SessionProfile::id)
                    .collect(java.util.stream.Collectors.toSet());
            retryWindows.keySet().retainAll(currentIds);
            pruneJobs(profiles);
            for (SessionProfile profile : profiles) {
                if (closed.get()) {
                    return;
                }
                if (!profile.enabled()
                        || !profile.scheduler().enabled()
                        || !sessionManager.hasAuthentication(profile)) {
                    continue;
                }
                long last = profile.state().lastAuthenticatedAtEpochMillis();
                long intervalMillis = TimeUnit.SECONDS.toMillis(profile.scheduler().intervalSeconds());
                if (last == 0L || now - last >= intervalMillis || profile.state().missingOrExpired(now)) {
                    dispatch(profile, false);
                }
            }
        } catch (RuntimeException ignored) {
            // Scheduler failures must never affect Burp traffic.
        }
    }

    private void dispatch(SessionProfile profile, boolean startup) {
        RetryWindow retry = retryWindows.get(profile.id());
        if (closed.get() || (retry != null && retry.blocks(profile, clock.millis()))
                || !inFlight.add(profile.id())) {
            return;
        }
        String actorKey = profile.usesLinkedAuthProfile() ? profile.linkedAuthProfileId() : null;
        if (actorKey != null && !actorsInFlight.add(actorKey)) {
            inFlight.remove(profile.id());
            return;
        }
        JobRuntime previous = jobs.getOrDefault(profile.id(), JobRuntime.empty());
        jobs.put(profile.id(), previous.withPhase(Phase.QUEUED));
        try {
            workers.execute(() -> refreshProfileSafely(profile.id(), actorKey, startup));
        } catch (RejectedExecutionException ignored) {
            // A full queue is retried on a later tick; never run network I/O on the timer.
            jobs.put(profile.id(), previous);
            inFlight.remove(profile.id());
            if (actorKey != null) {
                actorsInFlight.remove(actorKey);
            }
        }
    }

    private void refreshProfileSafely(String profileId, String actorKey, boolean startup) {
        SessionProfile before = null;
        boolean attempted = false;
        String outcome = "SKIPPED";
        long startedAt = clock.millis();
        try {
            if (closed.get()) {
                return;
            }
            before = sessionManager.profile(profileId).orElse(null);
            if (before == null || !before.enabled() || !before.scheduler().enabled()
                    || !sessionManager.hasAuthentication(before)
                    || (startup && !before.scheduler().runAtStartup())
                    || !Objects.equals(actorKey, before.usesLinkedAuthProfile()
                            ? before.linkedAuthProfileId() : null)) {
                return;
            }
            attempted = true;
            jobs.compute(profileId, (id, previous) -> new JobRuntime(Phase.RUNNING, startedAt,
                    previous == null ? 0L : previous.completedAt(),
                    previous == null ? 0L : previous.durationMillis(), previous == null ? "" : previous.outcome()));
            // Recheck due time under the authentication lock after queueing/waiting.
            SessionAuthenticationResult result = startup ? sessionManager.maintainAuthenticationWithResult(profileId)
                    : sessionManager.refreshScheduledProfileWithResult(profileId);
            outcome = result.outcome().name();
        } catch (RuntimeException ignored) {
            // One broken scheduled auth flow must not stop other scheduled profiles.
            outcome = "FAILED";
        } finally {
            try {
                if (!closed.get() && attempted) {
                    SessionProfile after = sessionManager.profile(profileId).orElse(null);
                    if (after != null && after.state().lastAuthenticatedAtEpochMillis()
                            == before.state().lastAuthenticatedAtEpochMillis()) {
                        // Failed/deferred attempts must not retry every second with a stale marker.
                        retryWindows.put(profileId, new RetryWindow(after, clock.millis()));
                    } else {
                        retryWindows.remove(profileId);
                    }
                }
            } catch (RuntimeException ignored) {
                // Release admission even when persistence cannot be read.
            } finally {
                long completedAt = clock.millis();
                if (attempted) {
                    jobs.put(profileId, new JobRuntime(Phase.IDLE, startedAt, completedAt,
                            Math.max(0L, completedAt - startedAt), outcome));
                } else {
                    jobs.computeIfPresent(profileId, (id, job) -> job.withPhase(Phase.IDLE));
                }
                inFlight.remove(profileId);
                if (actorKey != null) {
                    actorsInFlight.remove(actorKey);
                }
            }
        }
    }

    private record RetryWindow(SessionProfile profile, long completedAt) {
        boolean blocks(SessionProfile current, long now) {
            return matches(current)
                    && now - completedAt < TimeUnit.SECONDS.toMillis(current.scheduler().intervalSeconds());
        }

        boolean matches(SessionProfile current) {
            return profile.scheduler().equals(current.scheduler())
                    && profile.authConfig().equals(current.authConfig())
                    && profile.linkedAuthProfileId().equals(current.linkedAuthProfileId())
                    && profile.state().lastAuthenticatedAtEpochMillis()
                            == current.state().lastAuthenticatedAtEpochMillis();
        }
    }

    private enum Phase { IDLE, QUEUED, RUNNING }

    private record JobRuntime(Phase phase, long startedAt, long completedAt, long durationMillis, String outcome) {
        static JobRuntime empty() { return new JobRuntime(Phase.IDLE, 0L, 0L, 0L, ""); }
        JobRuntime withPhase(Phase updated) {
            return new JobRuntime(updated, startedAt, completedAt, durationMillis, outcome);
        }
    }
}
