package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepared target request that has been rendered and had auth applied, but has not been sent.
 */
public final class PreparedAccessRequest {
    private final boolean success;
    private final String reason;
    private final Optional<StoredRequestTemplate> request;
    private final String rawRequest;
    private final List<String> warnings;

    private PreparedAccessRequest(
            boolean success,
            String reason,
            Optional<StoredRequestTemplate> request,
            String rawRequest,
            List<String> warnings) {
        this.success = success;
        this.reason = Objects.requireNonNullElse(reason, "");
        this.request = Objects.requireNonNull(request, "request");
        this.rawRequest = Objects.requireNonNullElse(rawRequest, "");
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
    }

    /**
     * Creates a successful prepared request.
     *
     * @param request rendered request
     * @param rawRequest raw request evidence
     * @param warnings non-fatal warnings
     * @return prepared request
     */
    public static PreparedAccessRequest success(
            StoredRequestTemplate request,
            String rawRequest,
            List<String> warnings) {
        return new PreparedAccessRequest(
                true,
                "",
                Optional.of(Objects.requireNonNull(request, "request")),
                Objects.requireNonNull(rawRequest, "rawRequest"),
                warnings);
    }

    /**
     * Creates an invalid prepared request.
     *
     * @param reason failure reason
     * @param warnings non-fatal warnings
     * @return invalid prepared request
     */
    public static PreparedAccessRequest invalid(String reason, List<String> warnings) {
        return new PreparedAccessRequest(false, reason, Optional.empty(), "", warnings);
    }

    /** @return true when the request is ready to send */
    public boolean success() {
        return success;
    }

    /** @return failure reason, or empty */
    public String reason() {
        return reason;
    }

    /** @return rendered request when successful */
    public Optional<StoredRequestTemplate> request() {
        return request;
    }

    /** @return raw request evidence */
    public String rawRequest() {
        return rawRequest;
    }

    /** @return non-fatal warnings */
    public List<String> warnings() {
        return warnings;
    }
}
