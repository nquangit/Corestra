package dev.nquangit.corestra.flow.execution;

import java.util.Objects;

/**
 * Composes an {@link AggregatedOutput} into a synthetic response body string suitable for
 * injection into a flow step's response preview.
 *
 * <p>The composition is intentionally straightforward: the aggregated body is returned as-is,
 * allowing the caller to inject it directly into the final step's response.
 */
public final class AggregatedResponseComposer {

    /**
     * Composes the supplied aggregated output into a synthetic response body string.
     *
     * @param aggregatedOutput the aggregated output to compose; must not be {@code null}
     * @return the composed body string, never {@code null}
     */
    public String compose(AggregatedOutput aggregatedOutput) {
        Objects.requireNonNull(aggregatedOutput, "aggregatedOutput");
        return aggregatedOutput.body();
    }
}
