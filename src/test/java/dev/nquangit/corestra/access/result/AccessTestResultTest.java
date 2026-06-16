package dev.nquangit.corestra.access.result;

import dev.nquangit.corestra.access.matrix.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessTestResultTest {
    @Test
    void separatesRunStatusFromVerdictAndSupportsSuspiciousCompletedResult() {
        AccessTestResult result = new AccessTestResult(
                "result-1",
                "case-1",
                RunStatus.COMPLETED,
                Verdict.SUSPICIOUS,
                "Expected deny but response looked allowed",
                1000L,
                1500L,
                "GET /accounts/1",
                "HTTP 200",
                List.of("status mismatch"));

        assertEquals(RunStatus.COMPLETED, result.runStatus());
        assertEquals(Verdict.SUSPICIOUS, result.verdict());
        assertEquals("Expected deny but response looked allowed", result.reason());
    }

    @Test
    void warningsAreDefensivelyCopiedAndUnmodifiable() {
        List<String> warnings = new ArrayList<>();
        warnings.add("render warning");

        AccessTestResult result = new AccessTestResult(
                "result-1",
                "case-1",
                RunStatus.COMPLETED,
                Verdict.REVIEW,
                "",
                1000L,
                1500L,
                "",
                "",
                warnings);
        warnings.add("changed");

        assertEquals(List.of("render warning"), result.warnings());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().add("new"));
    }
}
