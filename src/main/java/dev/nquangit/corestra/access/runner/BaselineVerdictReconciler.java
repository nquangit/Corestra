package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-checks DENY verdicts against the ALLOW baseline of the same target and data in one run.
 *
 * <p>A denial only proves access control when the legitimate actor can reach the same target.
 * If every ALLOW baseline row for a target/data pair in the run failed (for example the path is
 * wrong and every actor gets 404, or the server is erroring), a DENY row that was marked OK is
 * downgraded to REVIEW instead of reporting a false "protected" result.</p>
 */
public final class BaselineVerdictReconciler {

    /**
     * Returns adjusted results for DENY rows whose ALLOW baseline failed.
     *
     * @param rows rows that ran, in run order
     * @param resultsByRowId latest result per row id from this run
     * @return adjusted results keyed by row id; rows that need no change are absent
     */
    public Map<String, AccessTestResult> reconcile(
            List<AccessTestCase> rows,
            Map<String, AccessTestResult> resultsByRowId) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(resultsByRowId, "resultsByRowId");
        Map<String, Baseline> baselines = new LinkedHashMap<>();
        for (AccessTestCase row : rows) {
            AccessTestResult result = resultsByRowId.get(row.id());
            // COMPLETED rows count by verdict; FAILED rows (transport/auth errors) are failed baselines.
            if (result == null || row.expected() != ExpectedResult.ALLOW
                    || (result.runStatus() != RunStatus.COMPLETED && result.runStatus() != RunStatus.FAILED)) {
                continue;
            }
            Baseline baseline = baselines.computeIfAbsent(key(row), ignored -> new Baseline());
            baseline.rows++;
            if (result.runStatus() == RunStatus.COMPLETED && result.verdict() == Verdict.OK) {
                baseline.succeeded = true;
            } else if (baseline.firstFailure.isEmpty()) {
                baseline.firstFailure = row.name() + ": " + result.reason();
            }
        }

        Map<String, AccessTestResult> adjusted = new LinkedHashMap<>();
        for (AccessTestCase row : rows) {
            AccessTestResult result = resultsByRowId.get(row.id());
            if (result == null || row.expected() != ExpectedResult.DENY || result.verdict() != Verdict.OK) {
                continue;
            }
            Baseline baseline = baselines.get(key(row));
            if (baseline == null || baseline.succeeded) {
                continue;
            }
            List<String> warnings = new ArrayList<>(result.warnings());
            warnings.add("ALLOW baseline failed for the same target and data: " + baseline.firstFailure);
            adjusted.put(row.id(), new AccessTestResult(
                    result.id(),
                    result.testCaseId(),
                    result.runStatus(),
                    Verdict.REVIEW,
                    "Denied, but the ALLOW baseline for the same target and data also failed;"
                            + " the denial does not prove access control (" + result.reason() + ")",
                    result.startedAtEpochMillis(),
                    result.finishedAtEpochMillis(),
                    result.renderedRequestSummary(),
                    result.responseSummary(),
                    warnings));
        }
        return adjusted;
    }

    private static String key(AccessTestCase row) {
        String target = switch (row.targetType()) {
            case REQUEST_TEMPLATE -> "template:" + row.templateId();
            case FLOW_TARGET -> "flow-target:" + row.flowTargetId();
        };
        return target + "\u0000" + row.target().dataProfileId();
    }

    private static final class Baseline {
        private int rows;
        private boolean succeeded;
        private String firstFailure = "";
    }
}
