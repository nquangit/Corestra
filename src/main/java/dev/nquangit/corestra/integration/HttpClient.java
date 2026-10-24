package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.session.SessionExecutionBinding;
import dev.nquangit.corestra.session.SessionBindingException;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.persistence.HttpSendMode;

/**
 * Transport layer abstraction for sending HTTP requests.
 * <p>
 * This interface decouples the flow engine from any concrete Burp Suite API,
 * allowing the engine to be tested with a fake implementation.
 */
public interface HttpClient {

    /**
     * Sends an HTTP request and returns the response.
     *
     * @param request the request to send
     * @return the response received
     * @throws RuntimeException if the request cannot be sent
     */
    HttpResponse send(HttpRequest request);

    /**
     * Sends with a request-specific route, or null to inherit current Project settings.
     * Routing transports honor the override; simple fixed transports retain their existing route.
     * Decorators must forward the override with the request, including after mutation.
     *
     * @param request request to send
     * @param sendModeOverride optional transport override
     * @return received response
     */
    default HttpResponse send(HttpRequest request, HttpSendMode sendModeOverride) {
        return send(request);
    }
    /** Prepares the final request after step rules, including execution-specific authentication when supported. */
    default HttpRequest prepareForExecution(HttpRequest request, ExecutionContext context) {
        if (SessionExecutionBinding.isBound(context)) {
            throw new SessionBindingException("Transport does not support bound session execution");
        }
        return request;
    }

    /** Sends a prepared request with immutable per-execution metadata; ordinary transports retain existing behavior. */
    default HttpResponse send(HttpRequest request, HttpSendMode mode, ExecutionContext context) {
        if (SessionExecutionBinding.isBound(context)) {
            throw new SessionBindingException("Transport does not support bound session execution");
        }
        return send(request, mode);
    }

}
