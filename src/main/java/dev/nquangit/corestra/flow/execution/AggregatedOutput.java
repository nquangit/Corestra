package dev.nquangit.corestra.flow.execution;

import java.util.Objects;

/**
 * Holds the result of aggregating multiple step execution records into a single body string.
 *
 * <p>Instances are immutable and produced by {@link ResponseAggregator#aggregate}.
 */
public final class AggregatedOutput {

    private final String body;

    /**
     * Creates an aggregated output with the given body.
     *
     * @param body the aggregated body text; may be empty but not {@code null}
     */
    public AggregatedOutput(String body) {
        this.body = body == null ? "" : body;
    }

    /**
     * Returns an empty aggregated output.
     *
     * @return an empty aggregated output
     */
    public static AggregatedOutput empty() {
        return new AggregatedOutput("");
    }

    /**
     * Returns the aggregated body text.
     *
     * @return the aggregated body text, never {@code null}
     */
    public String body() {
        return body;
    }

    /**
     * Returns whether the aggregated output is empty.
     *
     * @return {@code true} when the body is empty
     */
    public boolean isEmpty() {
        return body.isEmpty();
    }

    /**
     * Compares this output to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AggregatedOutput that)) {
            return false;
        }
        return Objects.equals(body, that.body);
    }

    /**
     * Returns the hash code for this output.
     *
     * @return the hash code for this output
     */
    @Override
    public int hashCode() {
        return Objects.hash(body);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AggregatedOutput{bodyLength=" + body.length() + '}';
    }
}
