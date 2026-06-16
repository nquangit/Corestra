package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs enabled access test cases sequentially.
 */
public final class AccessPlanRunner {
    private final InMemoryAccessRepository repository;
    private final SingleAccessTestRunner singleRunner;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a sequential access plan runner.
     *
     * @param repository in-memory access repository
     * @param singleRunner single-row runner
     */
    public AccessPlanRunner(InMemoryAccessRepository repository, SingleAccessTestRunner singleRunner) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.singleRunner = Objects.requireNonNull(singleRunner, "singleRunner");
    }

    /**
     * Runs selected rows sequentially.
     *
     * @param config run config
     * @param listener progress listener
     * @return plan summary
     */
    public AccessPlanSummary run(AccessPlanRunConfig config, AccessPlanListener listener) {
        AccessPlanRunConfig runConfig = config == null ? AccessPlanRunConfig.defaults() : config;
        AccessPlanListener safeListener = listener == null ? new AccessPlanListener() { } : listener;
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Access plan is already running");
        }
        long startedAt = System.currentTimeMillis();
        AccessWorkspace snapshot = repository.snapshot();
        List<AccessTestCase> selectedRows = selectedRows(runConfig, snapshot);
        AccessPlanSummary.Builder summary = new AccessPlanSummary.Builder(selectedRows.size(), startedAt);
        safeListener.onPlanStarted(selectedRows.size());

        try {
            int index = 0;
            for (AccessTestCase row : selectedRows) {
                if (stopRequested.get()) {
                    AccessPlanSummary stopped = summary.build(true, System.currentTimeMillis());
                    safeListener.onPlanStopped(stopped);
                    return stopped;
                }
                index++;
                markStatus(row, RunStatus.RUNNING, row.lastRunAtEpochMillis());
                safeListener.onRowStarted(row.id(), index, selectedRows.size());
                AccessRunResult result = runConfig.dryRun()
                        ? singleRunner.dryRun(row, snapshot, runConfig.env(), runConfig.scenario(),
                        runConfig.legacy(), runConfig.renderMode())
                        : singleRunner.run(row, snapshot, runConfig.env(), runConfig.scenario(),
                        runConfig.legacy(), runConfig.renderMode());
                saveResult(row, result);
                summary.record(result);
                safeListener.onRowFinished(row.id(), result, index, selectedRows.size());
                if (runConfig.stopOnFirstError() && shouldStopOnResult(result)) {
                    AccessPlanSummary stopped = summary.build(true, System.currentTimeMillis());
                    safeListener.onPlanStopped(stopped);
                    return stopped;
                }
            }

            boolean stopped = stopRequested.get();
            AccessPlanSummary planSummary = summary.build(stopped, System.currentTimeMillis());
            if (stopped) {
                safeListener.onPlanStopped(planSummary);
            } else {
                safeListener.onPlanFinished(planSummary);
            }
            return planSummary;
        } finally {
            running.set(false);
            stopRequested.set(false);
        }
    }

    /**
     * Requests the plan to stop before starting the next row.
     */
    public void requestStop() {
        stopRequested.set(true);
    }

    /**
     * Returns whether a stop was requested.
     *
     * @return true when stop has been requested
     */
    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /**
     * Returns whether a plan is currently running.
     *
     * @return true when running
     */
    public boolean isRunning() {
        return running.get();
    }

    private List<AccessTestCase> selectedRows(AccessPlanRunConfig config, AccessWorkspace snapshot) {
        return snapshot.testCases().values().stream()
                .filter(row -> config.includeDisabledRows() || row.enabled())
                .toList();
    }

    private void saveResult(AccessTestCase original, AccessRunResult result) {
        repository.saveResult(result.result());
        result.evidence().ifPresent(repository::saveEvidence);
        markStatus(original, result.result().runStatus(), result.result().finishedAtEpochMillis());
    }

    private void markStatus(AccessTestCase original, RunStatus status, long lastRunAtEpochMillis) {
        AccessTestCase source = repository.testCase(original.id()).orElse(original);
        repository.saveTestCase(new AccessTestCase(
                source.id(),
                source.name(),
                source.targetType(),
                source.templateId(),
                source.flowTargetId(),
                source.flowId(),
                source.targetStepId(),
                source.setup(),
                source.target(),
                source.expected(),
                source.evaluationProfileId(),
                source.assertions(),
                source.postChecks(),
                source.teardown(),
                source.enabled(),
                status,
                lastRunAtEpochMillis));
    }

    private boolean shouldStopOnResult(AccessRunResult result) {
        AccessTestResult testResult = result.result();
        return testResult.runStatus() == RunStatus.INVALID
                || testResult.runStatus() == RunStatus.FAILED
                || testResult.runStatus() == RunStatus.SKIPPED
                || testResult.verdict() == Verdict.ERROR
                || testResult.verdict() == Verdict.SUSPICIOUS;
    }
}
