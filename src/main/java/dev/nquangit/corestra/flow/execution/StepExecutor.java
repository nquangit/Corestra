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
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.integration.HttpClient;

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
        boolean requireInputs = "true".equals(context.metadata().get(FlowInputValidator.REQUIRE_INPUTS));
        if (requireInputs) {
            List<String> missing = FlowInputValidator.missingStepVariables(step, context.variables());
            if (!missing.isEmpty()) {
                return injectionFailure(step, startedAt, "missing variables: " + String.join(", ", missing));
            }
        }

        // 1. Build MutableHttpRequest from template
        StoredRequestTemplate template = step.requestTemplate();
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
        String receivedResponse;
        int statusCode;

        try {
            HttpRequest montoyaRequest = buildMontoyaRequest(template, renderedRequestLine, renderedHeaders, renderedBody);
            response = httpClient.send(montoyaRequest, step.sendModeOverride());
            receivedResponse = response.toString();
            statusCode = response.statusCode();
        } catch (Exception e) {
            long completedAt = System.currentTimeMillis();
            return new StepExecutionRecord(
                    step.id(),
                    renderedRequest,
                    "",
                    0,
                    false,
                    "Request failed: " + e.getMessage(),
                    startedAt,
                    Math.max(completedAt, startedAt),
                    Map.of());
        }

        // 4. Build request snapshot for extraction
        HttpMessageSnapshot requestSnapshot = new HttpMessageSnapshot(
                renderedRequestLine,
                renderedHeaders,
                renderedBody);

        // 5. Build response snapshot
        String responseStatusLine = response.httpVersion() + " " + response.statusCode() + " " + response.reasonPhrase();
        List<String> responseHeaders = response.headers().stream()
                .map(HttpHeader::toString)
                .collect(Collectors.toList());
        String responseBody = response.bodyToString();
        HttpMessageSnapshot responseSnapshot = new HttpMessageSnapshot(
                responseStatusLine,
                responseHeaders,
                responseBody);

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

        // Reconstruct raw HTTP/1.1 wire bytes and create the request using HttpService for routing.
        // Host is stored separately in StoredRequestTemplate and stripped at capture time, so it
        // must be explicitly added here as the first header before the stored header lines.
        StringBuilder rawBuilder = new StringBuilder();
        rawBuilder.append(requestLine).append("\r\n");
        String hostValue = template.host();
        boolean defaultPort = ("https".equalsIgnoreCase(template.scheme()) && template.port() == 443)
                || ("http".equalsIgnoreCase(template.scheme()) && template.port() == 80);
        if (!defaultPort) hostValue += ":" + template.port();
        rawBuilder.append("Host: ").append(hostValue).append("\r\n");
        for (String header : headers) {
            rawBuilder.append(header).append("\r\n");
        }
        rawBuilder.append("\r\n");
        if (body != null && !body.isEmpty()) {
            rawBuilder.append(body);
        }

        boolean secure = "https".equalsIgnoreCase(template.scheme());
        HttpService service = HttpService.httpService(template.host(), template.port(), secure);
        return HttpRequest.httpRequest(service, rawBuilder.toString());
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private StepExecutionRecord injectionFailure(FlowStep step, long startedAt, String reason) {
        return new StepExecutionRecord(step.id(), "", "", 0, false,
                FlowInputValidator.FAILURE_PREFIX + reason, startedAt,
                Math.max(startedAt, System.currentTimeMillis()), Map.of());
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
