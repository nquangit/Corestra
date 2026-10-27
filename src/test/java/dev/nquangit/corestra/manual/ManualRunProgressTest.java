package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.StepExecutionProgress;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.nquangit.corestra.flow.execution.StepExecutionProgress.Phase;
import static dev.nquangit.corestra.manual.ManualRunProgress.State.*;

class ManualRunProgressTest {
    private static FlowDefinition flow() {
        var request = new StoredRequestTemplate("GET", "https", "api.test", 443, "/", List.of(), "");
        return new FlowDefinition("parent", "Parent", "", true, List.of(
                new FlowStep("one", "One", true, "", request, List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.CONTINUE_FLOW),
                new FlowStep("two", "Two", true, "", request, List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW),
                new FlowStep("off", "Off", false, "", request, List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW)), null, null, null);
    }
    private static StepExecutionProgress event(String flow, String step, StepExecutionProgress.Phase phase, int attempt, int code) {
        return new StepExecutionProgress("run", flow, flow, step, step, phase, attempt, code, "");
    }
    @Test void progressResetsForEachRunAndCountsRetriesAsOneRequest() {
        var progress = new ManualRunProgress(flow(), 0, 2, false);
        assertEquals(NOT_RUN, progress.snapshot().get("one").state()); assertEquals(SKIPPED, progress.snapshot().get("off").state());
        progress.accept(event("parent", "one", Phase.STARTED, 1, 0)); assertEquals(RUNNING, progress.snapshot().get("one").state());
        progress.accept(event("parent", "one", Phase.FAILED, 1, 0)); progress.accept(event("parent", "one", Phase.STARTED, 2, 0));
        assertEquals(RUNNING, progress.snapshot().get("one").state()); assertEquals(2, progress.snapshot().get("one").attempt());
        progress.accept(event("parent", "one", Phase.COMPLETED, 2, 200)); assertEquals("1/2 done", progress.summary());
        assertEquals(NOT_RUN, new ManualRunProgress(flow(), 0, 2, false).snapshot().get("one").state());
    }
    @Test void cancellationKeepsEarlierCompletedAndFailedRequestsAndLeavesUnstartedGray() {
        var progress = new ManualRunProgress(flow(), 0, 2, false);
        progress.accept(event("parent", "one", Phase.STARTED, 1, 0)); progress.accept(event("parent", "one", Phase.COMPLETED, 1, 200));
        progress.accept(event("parent", "two", Phase.STARTED, 1, 0)); progress.accept(event("parent", "two", Phase.FAILED, 1, 0)); progress.finish(true);
        assertEquals(COMPLETED, progress.snapshot().get("one").state()); assertEquals(STOPPED, progress.snapshot().get("two").state());
        progress.accept(event("parent", "two", Phase.COMPLETED, 1, 200)); assertEquals(STOPPED, progress.snapshot().get("two").state());
        var failed = new ManualRunProgress(flow(), 0, 2, false);
        failed.accept(event("parent", "one", Phase.FAILED, 1, 0)); failed.finish(false);
        assertEquals(FAILED, failed.snapshot().get("one").state()); assertEquals(NOT_RUN, failed.snapshot().get("two").state());
    }
    @Test void childIdsAreIgnoredAndSingleStepCanExplicitlyRunDisabledRequest() {
        var progress = new ManualRunProgress(flow(), 2, 2, true);
        progress.accept(event("child", "off", Phase.STARTED, 1, 0)); assertEquals(NOT_RUN, progress.snapshot().get("off").state());
        progress.accept(event("parent", "one", Phase.STARTED, 1, 0)); assertEquals(SKIPPED, progress.snapshot().get("one").state());
        progress.accept(event("parent", "off", Phase.STARTED, 1, 0)); assertEquals(RUNNING, progress.snapshot().get("off").state());
        progress.finish(false); assertEquals(FAILED, progress.snapshot().get("off").state());
    }
    @Test void restartingFlowClearsLaterColorsAndStopDoesNotMarkUnsentPreparationAsRunning() {
        var progress = new ManualRunProgress(flow(), 0, 2, false);
        progress.accept(event("parent", "one", Phase.STARTED, 1, 0)); progress.accept(event("parent", "one", Phase.COMPLETED, 1, 200));
        progress.accept(event("parent", "two", Phase.STARTED, 1, 0)); progress.accept(event("parent", "two", Phase.COMPLETED, 1, 500));
        progress.accept(event("parent", "one", Phase.STARTED, 2, 0));
        assertEquals(NOT_RUN, progress.snapshot().get("two").state()); assertEquals("0/2 done", progress.summary());
        progress.accept(event("parent", "one", Phase.FAILED, 2, 0), true);
        progress.accept(event("parent", "two", Phase.STARTED, 2, 0), true); progress.accept(event("parent", "two", Phase.FAILED, 2, 0), true);
        progress.finish(true);
        assertEquals(STOPPED, progress.snapshot().get("one").state()); assertEquals(NOT_RUN, progress.snapshot().get("two").state());
    }
}
