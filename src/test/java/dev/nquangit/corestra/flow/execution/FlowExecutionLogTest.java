package dev.nquangit.corestra.flow.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowExecutionLogTest {
    @Test
    void retainsNewestEntriesWithinCapacityAndNotifiesListeners() {
        FlowExecutionLog log = new FlowExecutionLog(2);
        AtomicInteger changes = new AtomicInteger();
        log.addListener(changes::incrementAndGet);

        log.record(result("one", 1));
        log.record(result("two", 2));
        log.record(result("three", 3));

        assertEquals(List.of("three", "two"),
                log.snapshot().stream().map(FlowExecutionLog.Entry::stepId).toList());
        assertEquals(3, changes.get());
        log.clear();
        assertEquals(List.of(), log.snapshot());
        assertEquals(4, changes.get());
    }

    private static ExecutionResult result(String stepId, int status) {
        StepExecutionRecord record = new StepExecutionRecord(stepId, "GET / HTTP/1.1\r\n\r\n",
                "HTTP/1.1 " + status + " OK\r\n\r\n", status, true, "", 10, 20, Map.of());
        return ExecutionResult.success("flow", "Flow", List.of(record));
    }
}
