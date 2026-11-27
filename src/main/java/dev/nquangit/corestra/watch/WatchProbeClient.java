package dev.nquangit.corestra.watch;

import java.util.List;
import java.util.Map;

/**
 * Sends a Watch's probe request. Implemented over Montoya by the live integration; faked in tests.
 *
 * <p>Implementations render {@code {{name}}} placeholders in the probe template from
 * {@code variables}, replace/add the {@code inheritedHeaders}, mark the send as Corestra-origin
 * traffic (so no Corestra handler reprocesses it), bound it by {@code timeoutMillis}, and never
 * throw: failures are returned as {@link ProbeResponse#error()}.</p>
 */
@FunctionalInterface
public interface WatchProbeClient {
    /**
     * Sends the probe.
     *
     * @param definition       watch definition (probe template inside)
     * @param variables        request variables for placeholder rendering
     * @param inheritedHeaders header lines {@code "Name: value"} copied from the live request
     * @param timeoutMillis    maximum time for this send
     * @return response or error
     */
    ProbeResponse fetch(WatchDefinition definition, Map<String, String> variables,
                        List<String> inheritedHeaders, long timeoutMillis);

    /**
     * Probe response.
     *
     * @param statusCode HTTP status, or 0 on error
     * @param headers    header lines
     * @param body       body text ("" when none)
     * @param error      non-null when the probe could not be sent/rendered or timed out
     */
    record ProbeResponse(int statusCode, List<String> headers, String body, String error) {
        /**
         * Normalizes nulls.
         *
         * @param statusCode status
         * @param headers    headers
         * @param body       body
         * @param error      error
         */
        public ProbeResponse {
            headers = headers == null ? List.of() : List.copyOf(headers);
            body = body == null ? "" : body;
        }

        /**
         * Returns an error response.
         *
         * @param error reason
         * @return error response
         */
        public static ProbeResponse failure(String error) {
            return new ProbeResponse(0, List.of(), "", error);
        }
    }
}
