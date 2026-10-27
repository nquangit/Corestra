package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.integration.HttpBodyBytes;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.integration.HttpClient;
import dev.nquangit.corestra.integration.HttpTransportException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Executes a single flow step by applying injection rules, sending the request, and
 * extracting variables from the response.
 *
 * <p>Execution proceeds as follows:
 * <ol>
 *   <li>The step's stored request template is used to build a {@link MutableHttpRequest}.</li>
 *   <li>Inject rules are applied via {@link InjectionService}.</li>
 *   <li>The mutated request is converted to a Montoya {@link HttpRequest} and sent via
 *       {@link HttpClient}.</li>
 *   <li>The response is captured and converted to an {@link HttpMessageSnapshot}.</li>
 *   <li>Extract rules are applied via {@link ExtractionService} and results are stored in
 *       the supplied {@link ExecutionContext}.</li>
 *   <li>A {@link StepExecutionRecord} describing the outcome is returned.</li>
 * </ol>
 *
 * <p>Any exception thrown during sending is caught and converted to a failed
 * {@link StepExecutionRecord}.
 *
 * <p>The {@link #buildMontoyaRequest} method is package-private to allow subclasses in tests
 * to substitute an alternative bridge without requiring the full Montoya runtime.
 */
public class StepExecutor {

    private final ExtractionService extractionService;
    private final InjectionService injectionService;

    /**
     * Creates a {@code StepExecutor}.
     *
     * @param extractionService the extraction service used to apply extract rules; must not be {@code null}
     * @param injectionService  the injection service used to apply inject rules; must not be {@code null}
     */
    public StepExecutor(ExtractionService extractionService, InjectionService injectionService) {
        this.extractionService = Objects.requireNonNull(extractionService, "extractionService");
        this.injectionService = Objects.requireNonNull(injectionService, "injectionService");
    }

    /**
     * Executes the supplied step, updating the context with any extracted variables.
     *
     * @param step       the flow step to execute; must not be {@code null}
     * @param context    the current execution context providing variables for injection; must not be {@code null}
     * @param httpClient the HTTP client used to send the request; must not be {@code null}
     * @return a {@link StepExecutionRecord} describing the step outcome
     */
    public StepExecutionRecord execute(FlowStep step, ExecutionContext context, HttpClient httpClient) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(httpClient, "httpClient");

        long startedAt = System.currentTimeMillis();
        // This gate is local to Manual Testing, including child flows. Keep the saved rules intact.
        if ("true".equals(context.metadata().get(dev.nquangit.corestra.manual.ManualRequestTemplate.ENABLED))
                && !step.manualInjectRulesEnabled()) {
            step = step.withInjectRules(List.of());
        }
        if ("true".equals(context.metadata().get(dev.nquangit.corestra.manual.ManualRequestTemplate.ENABLED)) && Thread.currentThread().isInterrupted())
            return injectionFailure(step, startedAt, new dev.nquangit.corestra.interaction.InteractionAbortedException("Manual run cancelled").getMessage());
        boolean requireInputs = "true".equals(context.metadata().get(FlowInputValidator.REQUIRE_INPUTS));
        if (requireInputs) {
            List<String> missing = FlowInputValidator.missingStepVariables(step, context.variables());
            if (!missing.isEmpty()) {
                return injectionFailure(step, startedAt, "missing variables: " + String.join(", ", missing));
            }
        }

        // 1. Build MutableHttpRequest from template
        StoredRequestTemplate template = step.requestTemplate();
        if ("true".equals(context.metadata().get(dev.nquangit.corestra.manual.ManualRequestTemplate.ENABLED))) {
            try { template = dev.nquangit.corestra.manual.ManualRequestTemplate.render(template, context.variables()); }
            catch (IllegalArgumentException exception) { return injectionFailure(step, startedAt, exception.getMessage()); }
        }
        String requestLine = template.method() + " " + template.pathAndQuery() + " HTTP/1.1";
        MutableHttpRequest mutableRequest = new MutableHttpRequest(
                requestLine,
                template.headerLines(),
                template.body());

        // 2. Apply inject rules
        RequestMutationResult mutationResult;
        try {
            mutationResult = requireInputs
                    ? injectionService.applyRequired(step.injectRules(), context.variables(), mutableRequest)
                    : injectionService.apply(step.injectRules(), context.variables(), mutableRequest);
        } catch (InteractionAbortedException exception) {
            return injectionFailure(step, startedAt, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            if (!requireInputs) throw exception;
            return injectionFailure(step, startedAt, exception.getMessage());
        }

        String renderedRequestLine = mutationResult.requestLine();
        List<String> renderedHeaders = mutationResult.headers();
        String renderedBody = mutationResult.body();

        // Build rendered request text for recording
        String renderedRequest = buildRenderedRequest(renderedRequestLine, renderedHeaders, renderedBody);

        // 3. Convert to Montoya HttpRequest and send
        HttpResponse response;
        HttpRequest montoyaRequest;
        String receivedResponse;
        int statusCode;
        boolean sending = false;

        try {
            montoyaRequest = buildMontoyaRequest(template, renderedRequestLine, renderedHeaders, renderedBody);
            montoyaRequest = httpClient.prepareForExecution(montoyaRequest, context);
            // Record and extract from the prepared native message, including its updated framing.
            renderedRequest = montoyaRequest.toString();
            renderedRequestLine = montoyaRequest.method() + " " + montoyaRequest.path() + " " + montoyaRequest.httpVersion();
            renderedHeaders = montoyaRequest.headers().stream().map(h -> h.name() + ": " + h.value()).toList();
            renderedBody = montoyaRequest.bodyToString();
            sending = true;
            response = httpClient.send(montoyaRequest, step.sendModeOverride(), context);
            if (response == null) throw new HttpTransportException(ExecutionFailureType.NO_RESPONSE, "No HTTP response received", null);
            receivedResponse = response.toString();
            statusCode = response.statusCode();
            if (statusCode <= 0) throw new HttpTransportException(ExecutionFailureType.NO_RESPONSE, "No valid HTTP response status received", null);
        } catch (Exception e) {
            long completedAt = System.currentTimeMillis();
            boolean interrupted = Thread.currentThread().isInterrupted() || HttpTransportException.wasInterrupted(e);
            ExecutionFailureType type = interrupted || InteractionAbortedException.isAborted(e.getMessage())
                    ? ExecutionFailureType.CANCELLED
                    : dev.nquangit.corestra.session.SessionBindingException.isAborted(e.getMessage())
                    ? ExecutionFailureType.SESSION_BINDING
                    : sending ? HttpTransportException.classify(e) : ExecutionFailureType.REQUEST_PREPARATION;
            if (interrupted) Thread.currentThread().interrupt();
            return new StepExecutionRecord(
                    step.id(),
                    renderedRequest,
                    "",
                    0,
                    false,
                    "Request failed: [" + type + "] " + e.getMessage(),
                    startedAt,
                    Math.max(completedAt, startedAt),
                    Map.of(), type);
        }

        // 4. Build request snapshot for extraction
        HttpMessageSnapshot requestSnapshot = new HttpMessageSnapshot(
                renderedRequestLine,
                renderedHeaders,
                renderedBody, HttpBodyBytes.readForExtraction(montoyaRequest, step.extractRules(), ExtractRule.SourceSide.REQUEST));

        // 5. Build response snapshot
        String responseStatusLine = response.httpVersion() + " " + response.statusCode() + " " + response.reasonPhrase();
        List<String> responseHeaders = response.headers().stream()
                .map(HttpHeader::toString)
                .collect(Collectors.toList());
        String responseBody = response.bodyToString();
        HttpMessageSnapshot responseSnapshot = new HttpMessageSnapshot(
                responseStatusLine,
                responseHeaders,
                responseBody, HttpBodyBytes.readForExtraction(response, step.extractRules(), ExtractRule.SourceSide.RESPONSE));

        // 6. Apply extract rules and collect extracted variables
        Map<String, String> extractedVariables = new LinkedHashMap<>();
        for (ExtractRule rule : step.extractRules()) {
            ExtractionResult result = extractionService.extract(rule, requestSnapshot, responseSnapshot);
            if (result.isSuccess()) {
                extractedVariables.put(result.targetVariable(), result.value());
            }
        }

        long completedAt = System.currentTimeMillis();

        return new StepExecutionRecord(
                step.id(),
                renderedRequest,
                receivedResponse,
                statusCode,
                true,
                "",
                startedAt,
                Math.max(completedAt, startedAt),
                extractedVariables);
    }

    /**
     * Builds a Montoya {@link HttpRequest} from the mutated request parts and the original
     * stored template (which supplies the host and port for the HTTP service).
     * Captured Content-Length headers are replaced using Montoya's body API so the
     * length reflects the final encoded bytes after injection or template rendering.
     *
     * @param template        the original stored request template supplying connection details
     * @param requestLine     the mutated request line
     * @param headers         the mutated headers
     * @param body            the mutated body
     * @return a Montoya {@code HttpRequest} ready to send
     */
    HttpRequest buildMontoyaRequest(
            StoredRequestTemplate template,
            String requestLine,
            List<String> headers,
            String body) {

        // Reconstruct HTTP/1.1 headers and create the request using HttpService for routing.
        // Host is stored separately in StoredRequestTemplate and stripped at capture time, so it
        // must be explicitly added here as the first header before the stored header lines.
        StringBuilder rawBuilder = new StringBuilder();
        rawBuilder.append(requestLine).append("\r\n");
        String hostValue = template.host();
        boolean defaultPort = ("https".equalsIgnoreCase(template.scheme()) && template.port() == 443)
                || ("http".equalsIgnoreCase(template.scheme()) && template.port() == 80);
        if (!defaultPort) hostValue += ":" + template.port();
        if (headers.stream().noneMatch(h -> h.regionMatches(true, 0, "Host:", 0, 5)))
            rawBuilder.append("Host: ").append(hostValue).append("\r\n");
        boolean hadContentLength = false;
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon >= 0 && "Content-Length".equalsIgnoreCase(header.substring(0, colon).trim())) {
                hadContentLength = true;
            } else {
                rawBuilder.append(header).append("\r\n");
            }
        }
        rawBuilder.append("\r\n");

        boolean secure = "https".equalsIgnoreCase(template.scheme());
        HttpService service = HttpService.httpService(template.host(), template.port(), secure);
        HttpRequest request = HttpRequest.httpRequest(service, rawBuilder.toString());
        // Let Burp apply its configured string encoding and calculate the byte length.
        // Preserve the absence of Content-Length on originally unframed empty requests.
        return hadContentLength || (body != null && !body.isEmpty())
                ? request.withBody(Objects.requireNonNullElse(body, "")) : request;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private StepExecutionRecord injectionFailure(FlowStep step, long startedAt, String reason) {
        return new StepExecutionRecord(step.id(), "", "", 0, false,
                FlowInputValidator.FAILURE_PREFIX + reason, startedAt,
                Math.max(startedAt, System.currentTimeMillis()), Map.of(),
                InteractionAbortedException.isAborted(reason) ? ExecutionFailureType.CANCELLED : ExecutionFailureType.INPUT_ERROR);
    }

    private String buildRenderedRequest(String requestLine, List<String> headers, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(requestLine).append("\r\n");
        for (String header : headers) {
            sb.append(header).append("\r\n");
        }
        sb.append("\r\n");
        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }
        return sb.toString();
    }
}
