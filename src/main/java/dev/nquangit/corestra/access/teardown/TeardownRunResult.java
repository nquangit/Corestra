package dev.nquangit.corestra.access.teardown;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result produced after attempting to run or dry-run a teardown flow.
 */
public final class TeardownRunResult {
    private final boolean executed;
    private final boolean success;
    private final String reason;
    private final List<String> warnings;
    private final Optional<TeardownEvidence> evidence;

    /**
     * Creates a teardown run result.
     *
     * @param executed whether teardown was attempted
     * @param success whether teardown completed successfully
     * @param reason human-readable reason
     * @param warnings warnings to attach to the access result
     * @param evidence redacted teardown evidence, when available
     */
    public TeardownRunResult(
            boolean executed,
            boolean success,
            String reason,
            List<String> warnings,
            Optional<TeardownEvidence> evidence) {
        this.executed = executed;
        this.success = success;
        this.reason = Objects.requireNonNullElse(reason, "");
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * Returns an unattempted teardown result.
     *
     * @return unattempted result
     */
    public static TeardownRunResult notConfigured() {
        return new TeardownRunResult(false, true, "", List.of(), Optional.empty());
    }

    /** @return whether teardown was attempted */
    public boolean executed() {
        return executed;
    }

    /** @return whether teardown completed successfully */
    public boolean success() {
        return success;
    }

    /** @return human-readable reason */
    public String reason() {
        return reason;
    }

    /** @return warnings to attach to the access result */
    public List<String> warnings() {
        return warnings;
    }

    /** @return redacted teardown evidence, when available */
    public Optional<TeardownEvidence> evidence() {
        return evidence;
    }
}
