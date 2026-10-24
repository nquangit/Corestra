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

    @Test
    void evictsOlderPayloadsWithinByteBudgetAndOmitsOversizedLogCopyOnly() {
        FlowExecutionLog log = new FlowExecutionLog(100, 120);
        log.record(result("one", 200));
        log.record(result("two", 200));
        assertEquals(List.of("two"), log.snapshot().stream().map(FlowExecutionLog.Entry::stepId).toList());
        String payload = "binary".repeat(100);
        var record = new StepExecutionRecord("large", "GET / HTTP/1.1", payload, 200, true, "", 1, 2, Map.of());
        var result = ExecutionResult.success("flow", "Flow", List.of(record));
        log.record(result);
        var entry = log.snapshot().getFirst();
        assertEquals("", entry.response());
        org.junit.jupiter.api.Assertions.assertTrue(entry.errorMessage().contains("Raw HTTP omitted"));
        assertEquals(payload, result.stepRecords().getFirst().receivedResponse());
        log.clear();
        log.record(result("reset", 200));
        assertEquals(1, log.snapshot().size());
    }

    private static ExecutionResult result(String stepId, int status) {
        StepExecutionRecord record = new StepExecutionRecord(stepId, "GET / HTTP/1.1\r\n\r\n",
                "HTTP/1.1 " + status + " OK\r\n\r\n", status, true, "", 10, 20, Map.of());
        return ExecutionResult.success("flow", "Flow", List.of(record));
    }
}
