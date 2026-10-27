package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Marker;
import burp.api.montoya.http.message.Cookie;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.responses.analysis.Attribute;
import burp.api.montoya.http.message.responses.analysis.AttributeType;
import burp.api.montoya.http.message.responses.analysis.KeywordCount;
import burp.api.montoya.http.message.StatusCodeClass;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FlowEngine}.
 *
 * <p>Uses hand-crafted fakes for {@link HttpClient} and {@link HttpResponse} — no Mockito.
 */
class FlowEngineTest {

    private ExtractionService extractionService;
    private InjectionService injectionService;

    @BeforeEach
    void setUp() {
        extractionService = new ExtractionService();
        injectionService = new InjectionService();
    }

    /**
     * Creates a {@link FlowEngine} backed by a test-friendly {@link StepExecutor} that
     * bypasses the Montoya {@link HttpRequest} factory (which requires a real Burp runtime).
     */
    private FlowEngine engineWith(HttpClient client) {
        StepExecutor testStepExecutor = new StepExecutor(extractionService, injectionService) {
            @Override
            HttpRequest buildMontoyaRequest(
                    StoredRequestTemplate template,
                    String requestLine,
                    java.util.List<String> headers,
                    String body) {
                // Return a no-op stub — the fake HttpClient ignores it anyway
                return TestFlowEngineFactory.storedRequest(template, requestLine, headers, body);
            }
        };
        return new FlowEngine(testStepExecutor, client);
    }

    @Test
    void cancelledInteractiveInputStopsEvenContinueFlowAndNeverSendsCapturedRequest() {
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        injectionService = new InjectionService((name, config) -> java.util.Optional.empty());
        var input = new dev.nquangit.corestra.flow.rule.InjectRule("input", "OTP", true,
                dev.nquangit.corestra.flow.rule.InjectRule.Target.REQUEST_HEADER, "X-OTP",
                dev.nquangit.corestra.flow.rule.InjectRule.ValueSourceType.INTERACTIVE,
                "", "", dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy.OVERWRITE);
        FlowStep first = enabledStep("first", StepErrorStrategy.CONTINUE_FLOW).withInjectRules(List.of(input));
        FlowDefinition flow = twoStepFlow(first, enabledStep("second", StepErrorStrategy.FAIL_FLOW));
        ExecutionResult result = engineWith(request -> { sends.incrementAndGet(); return null; }).execute(flow, freshContext());
        assertFalse(result.success());
        assertTrue(dev.nquangit.corestra.interaction.InteractionAbortedException.isAborted(result.failureMessage()));
        assertEquals(1, result.stepRecords().size());
        assertEquals(0, sends.get());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static StoredRequestTemplate minimalTemplate() {
        return new StoredRequestTemplate(
                "GET",
                "http",
                "example.com",
                80,
                "/test",
                List.of("Host: example.com"),
                "");
    }

    private static FlowStep enabledStep(String id, StepErrorStrategy errorStrategy) {
        return new FlowStep(
                id,
                "Step " + id,
                true,
                "",
                minimalTemplate(),
                List.of(),
                List.of(),
                List.of(),
                errorStrategy);
    }

    private static FlowStep disabledStep(String id) {
        return new FlowStep(
                id,
                "Step " + id,
                false,
                "",
                minimalTemplate(),
                List.of(),
                List.of(),
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static FlowStep stepWithExtractRule(String id, ExtractRule rule) {
        return new FlowStep(
                id,
                "Step " + id,
                true,
                "",
                minimalTemplate(),
                List.of(rule),
                List.of(),
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static FlowDefinition singleStepFlow(FlowStep step) {
        return new FlowDefinition("flow1", "Flow One", "", true, List.of(step), null, null, null);
    }

    private static FlowDefinition twoStepFlow(FlowStep step1, FlowStep step2) {
        return new FlowDefinition("flow1", "Flow One", "", true, List.of(step1, step2), null, null, null);
    }

    private static ExecutionContext freshContext() {
        return ExecutionContext.start("run1", "flow1", Map.of(), 1_000L);
    }

    /** Creates a stub {@link HttpResponse} that returns canned values. */
    private static HttpResponse cannedResponse(short statusCode, String reasonPhrase, String body) {
        return new StubHttpResponse(statusCode, reasonPhrase, body);
    }

    /** Always-success fake HttpClient that returns the given canned response. */
    private static HttpClient alwaysSuccessClient(HttpResponse response) {
        return request -> response;
    }

    /** HttpClient that throws a RuntimeException to simulate a transport failure. */
    private static HttpClient alwaysFailClient(String message) {
        return request -> { throw new RuntimeException(message); };
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void execute_skipsDisabledSteps() {
        FlowStep disabled = disabledStep("step-disabled");
        FlowDefinition flow = singleStepFlow(disabled);

        HttpClient client = alwaysSuccessClient(cannedResponse((short) 200, "OK", ""));
        FlowEngine engine = engineWith(client);

        ExecutionResult result = engine.execute(flow, freshContext());

        assertTrue(result.success(), "Flow with only disabled steps should succeed");
        assertTrue(result.stepRecords().isEmpty(), "Disabled steps must not be recorded");
    }

    @Test
    void execute_recordsEnabledStep() {
        FlowStep step = enabledStep("step1", StepErrorStrategy.FAIL_FLOW);
        FlowDefinition flow = singleStepFlow(step);

        HttpClient client = alwaysSuccessClient(cannedResponse((short) 200, "OK", "hello"));
        FlowEngine engine = engineWith(client);

        ExecutionResult result = engine.execute(flow, freshContext());

        assertTrue(result.success());
        assertEquals(1, result.stepRecords().size());
        assertEquals("step1", result.stepRecords().get(0).stepId());
        assertTrue(result.stepRecords().get(0).success());
    }

    @Test
    void execute_variableExtractedInStep1_isAvailableInStep2() {
        // Step 1 extracts the response body into variable "token"
        ExtractRule extractBodyRule = new ExtractRule(
                "r1", "Extract token", true,
                SourceSide.RESPONSE,
                ExtractorType.BODY_TEXT,
                null,
                "token",
                List.of());

        FlowStep step1 = stepWithExtractRule("step1", extractBodyRule);
        FlowStep step2 = enabledStep("step2", StepErrorStrategy.FAIL_FLOW);

        FlowDefinition flow = twoStepFlow(step1, step2);

        // step1 returns "SECRET", step2 returns "DONE"
        HttpResponse responseForStep1 = cannedResponse((short) 200, "OK", "SECRET");
        HttpResponse responseForStep2 = cannedResponse((short) 200, "OK", "DONE");

        HttpClient client = new SequentialHttpClient(responseForStep1, responseForStep2);

        FlowEngine engine = engineWith(client);
        ExecutionResult result = engine.execute(flow, freshContext());

        assertTrue(result.success(), "Flow should succeed");
        assertEquals(2, result.stepRecords().size());

        StepExecutionRecord step1Record = result.stepRecords().get(0);
        assertEquals("step1", step1Record.stepId());
        assertEquals("SECRET", step1Record.extractedVariables().get("token"),
                "Token should have been extracted from step1 response body");
    }

    @Test
    void execute_failFlow_stopsEngineAfterFailedStep() {
        FlowStep step1 = enabledStep("step1", StepErrorStrategy.FAIL_FLOW);
        FlowStep step2 = enabledStep("step2", StepErrorStrategy.FAIL_FLOW);

        FlowDefinition flow = twoStepFlow(step1, step2);

        // step1 will fail at transport level
        HttpClient client = alwaysFailClient("connection refused");

        FlowEngine engine = engineWith(client);
        ExecutionResult result = engine.execute(flow, freshContext());

        assertFalse(result.success(), "Flow should fail");
        assertEquals(1, result.stepRecords().size(), "Only step1 should be recorded before engine stops");
        assertEquals("step1", result.stepRecords().get(0).stepId());
        assertFalse(result.stepRecords().get(0).success());
        assertNotNull(result.failureMessage());
        assertTrue(result.failureMessage().contains("step1"));
    }

    @Test
    void execute_continueFlow_continuesAfterFailedStep() {
        FlowStep step1 = enabledStep("step1", StepErrorStrategy.CONTINUE_FLOW);
        FlowStep step2 = enabledStep("step2", StepErrorStrategy.FAIL_FLOW);

        FlowDefinition flow = twoStepFlow(step1, step2);

        // step1 fails, step2 succeeds
        HttpResponse step2Response = cannedResponse((short) 200, "OK", "");
        HttpClient client = new HttpClient() {
            private int callCount = 0;
            @Override
            public burp.api.montoya.http.message.responses.HttpResponse send(HttpRequest request) {
                callCount++;
                if (callCount == 1) {
                    throw new RuntimeException("step1 failure");
                }
                return step2Response;
            }
        };

        FlowEngine engine = engineWith(client);
        ExecutionResult result = engine.execute(flow, freshContext());

        assertTrue(result.success(), "Flow should succeed because CONTINUE_FLOW was used for step1");
        assertEquals(2, result.stepRecords().size(), "Both steps should be recorded");
        assertFalse(result.stepRecords().get(0).success(), "Step1 should have failed");
        assertTrue(result.stepRecords().get(1).success(), "Step2 should have succeeded");
    }

    @Test
    void execute_mixedEnabledDisabledSteps_onlyEnabledAreRecorded() {
        FlowStep step1 = enabledStep("step1", StepErrorStrategy.FAIL_FLOW);
        FlowStep stepDisabled = disabledStep("stepDisabled");
        FlowStep step3 = enabledStep("step3", StepErrorStrategy.FAIL_FLOW);

        FlowDefinition flow = new FlowDefinition(
                "flow1", "Flow One", "", true,
                List.of(step1, stepDisabled, step3),
                null, null, null);

        HttpClient client = alwaysSuccessClient(cannedResponse((short) 200, "OK", ""));
        FlowEngine engine = engineWith(client);

        ExecutionResult result = engine.execute(flow, freshContext());

        assertTrue(result.success());
        assertEquals(2, result.stepRecords().size());
        assertEquals("step1", result.stepRecords().get(0).stepId());
        assertEquals("step3", result.stepRecords().get(1).stepId());
    }

    @Test
    void execute_flowSuccessCarriesCorrectFlowIdAndName() {
        FlowStep step = enabledStep("step1", StepErrorStrategy.FAIL_FLOW);
        FlowDefinition flow = new FlowDefinition("my-flow-id", "My Flow", "", true, List.of(step), null, null, null);

        HttpClient client = alwaysSuccessClient(cannedResponse((short) 200, "OK", ""));
        FlowEngine engine = engineWith(client);

        ExecutionResult result = engine.execute(flow, freshContext());

        assertTrue(result.success());
        assertEquals("my-flow-id", result.flowId());
        assertEquals("My Flow", result.flowName());
    }

    @Test
    void execute_flowFailureCarriesCorrectFlowIdAndName() {
        FlowStep step = enabledStep("step1", StepErrorStrategy.FAIL_FLOW);
        FlowDefinition flow = new FlowDefinition("my-flow-id", "My Flow", "", true, List.of(step), null, null, null);

        HttpClient client = alwaysFailClient("network error");
        FlowEngine engine = engineWith(client);

        ExecutionResult result = engine.execute(flow, freshContext());

        assertFalse(result.success());
        assertEquals("my-flow-id", result.flowId());
        assertEquals("My Flow", result.flowName());
        assertNotNull(result.failureMessage());
    }

    // -----------------------------------------------------------------------
    // Constructor validation
    // -----------------------------------------------------------------------

    @Test
    void constructor_rejectsNullExtractionService() {
        HttpClient client = alwaysSuccessClient(cannedResponse((short) 200, "OK", ""));
        assertThrows(NullPointerException.class,
                () -> new FlowEngine(null, injectionService, client));
    }

    @Test
    void constructor_rejectsNullInjectionService() {
        HttpClient client = alwaysSuccessClient(cannedResponse((short) 200, "OK", ""));
        assertThrows(NullPointerException.class,
                () -> new FlowEngine(extractionService, null, client));
    }

    @Test
    void constructor_rejectsNullHttpClient() {
        assertThrows(NullPointerException.class,
                () -> new FlowEngine(extractionService, injectionService, null));
    }

    // -----------------------------------------------------------------------
    // Fakes / stubs
    // -----------------------------------------------------------------------

    /**
     * Fake {@link HttpClient} that returns responses in declaration order, cycling through
     * the supplied array.
     */
    private static final class SequentialHttpClient implements HttpClient {
        private final HttpResponse[] responses;
        private int index = 0;

        SequentialHttpClient(HttpResponse... responses) {
            this.responses = responses;
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            if (index >= responses.length) {
                throw new IllegalStateException("More requests than canned responses");
            }
            return responses[index++];
        }
    }

    /**
     * Minimal stub for the Montoya {@link HttpResponse} interface that returns
     * canned status code, reason phrase, and body values.
     */
    private static final class StubHttpResponse implements HttpResponse {
        private final short statusCode;
        private final String reasonPhrase;
        private final String body;

        StubHttpResponse(short statusCode, String reasonPhrase, String body) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.body = body;
        }

        @Override public short statusCode() { return statusCode; }
        @Override public String reasonPhrase() { return reasonPhrase; }
        @Override public String httpVersion() { return "HTTP/1.1"; }
        @Override public String bodyToString() { return body; }
        @Override public ByteArray body() { return ByteArray.byteArray(body.getBytes()); }
        @Override public int bodyOffset() { return 0; }
        @Override public List<HttpHeader> headers() { return List.of(); }
        @Override public String toString() { return "HTTP/1.1 " + statusCode + " " + reasonPhrase + "\r\n\r\n" + body; }

        // --- Remaining interface methods that are not used in tests ---
        @Override public boolean isStatusCodeClass(StatusCodeClass c) { throw new UnsupportedOperationException(); }
        @Override public List<Cookie> cookies() { throw new UnsupportedOperationException(); }
        @Override public Cookie cookie(String name) { throw new UnsupportedOperationException(); }
        @Override public String cookieValue(String name) { throw new UnsupportedOperationException(); }
        @Override public boolean hasCookie(String name) { throw new UnsupportedOperationException(); }
        @Override public boolean hasCookie(Cookie cookie) { throw new UnsupportedOperationException(); }
        @Override public MimeType mimeType() { throw new UnsupportedOperationException(); }
        @Override public MimeType statedMimeType() { throw new UnsupportedOperationException(); }
        @Override public MimeType inferredMimeType() { throw new UnsupportedOperationException(); }
        @Override public List<KeywordCount> keywordCounts(String... keywords) { throw new UnsupportedOperationException(); }
        @Override public List<Attribute> attributes(AttributeType... types) { throw new UnsupportedOperationException(); }
        @Override public boolean hasHeader(HttpHeader header) { throw new UnsupportedOperationException(); }
        @Override public boolean hasHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public boolean hasHeader(String name, String value) { throw new UnsupportedOperationException(); }
        @Override public HttpHeader header(String name) { throw new UnsupportedOperationException(); }
        @Override public String headerValue(String name) { throw new UnsupportedOperationException(); }
        @Override public List<Marker> markers() { throw new UnsupportedOperationException(); }
        @Override public boolean contains(String text, boolean caseInsensitive) { throw new UnsupportedOperationException(); }
        @Override public boolean contains(Pattern pattern) { throw new UnsupportedOperationException(); }
        @Override public ByteArray toByteArray() { throw new UnsupportedOperationException(); }
        @Override public HttpResponse copyToTempFile() { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withStatusCode(short code) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withReasonPhrase(String phrase) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withHttpVersion(String version) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withBody(String body) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withBody(ByteArray body) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withAddedHeader(HttpHeader header) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withAddedHeader(String name, String value) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withAddedHeaders(List<? extends HttpHeader> headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withAddedHeaders(HttpHeader... headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withUpdatedHeader(HttpHeader header) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withUpdatedHeader(String name, String value) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withUpdatedHeaders(List<? extends HttpHeader> headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withUpdatedHeaders(HttpHeader... headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withRemovedHeader(HttpHeader header) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withRemovedHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withRemovedHeaders(List<? extends HttpHeader> headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withRemovedHeaders(HttpHeader... headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withMarkers(List<Marker> markers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse withMarkers(Marker... markers) { throw new UnsupportedOperationException(); }
    }

    /**
     * Minimal stub for the Montoya {@link HttpRequest} interface, returned by the test
     * {@link StepExecutor} subclass so no actual Montoya runtime is needed.
     */
    private static final class StubHttpRequest implements HttpRequest {
        @Override public boolean isInScope() { throw new UnsupportedOperationException(); }
        @Override public burp.api.montoya.http.HttpService httpService() { throw new UnsupportedOperationException(); }
        @Override public String url() { throw new UnsupportedOperationException(); }
        @Override public String method() { return "GET"; }
        @Override public String path() { return "/test"; }
        @Override public String query() { return ""; }
        @Override public String pathWithoutQuery() { return "/test"; }
        @Override public String fileExtension() { return ""; }
        @Override public burp.api.montoya.http.message.ContentType contentType() { throw new UnsupportedOperationException(); }
        @Override public List<burp.api.montoya.http.message.params.ParsedHttpParameter> parameters() { return List.of(); }
        @Override public List<burp.api.montoya.http.message.params.ParsedHttpParameter> parameters(burp.api.montoya.http.message.params.HttpParameterType t) { return List.of(); }
        @Override public boolean hasParameters() { return false; }
        @Override public boolean hasParameters(burp.api.montoya.http.message.params.HttpParameterType t) { return false; }
        @Override public burp.api.montoya.http.message.params.ParsedHttpParameter parameter(String name, burp.api.montoya.http.message.params.HttpParameterType t) { return null; }
        @Override public String parameterValue(String name, burp.api.montoya.http.message.params.HttpParameterType t) { return null; }
        @Override public burp.api.montoya.http.message.params.ParsedHttpParameter parameter(String name) { return null; }
        @Override public String parameterValue(String name) { return null; }
        @Override public boolean hasParameter(String name, burp.api.montoya.http.message.params.HttpParameterType t) { return false; }
        @Override public boolean hasParameter(burp.api.montoya.http.message.params.HttpParameter p) { return false; }
        @Override public boolean hasHeader(HttpHeader h) { return false; }
        @Override public boolean hasHeader(String name) { return false; }
        @Override public boolean hasHeader(String name, String value) { return false; }
        @Override public HttpHeader header(String name) { return null; }
        @Override public String headerValue(String name) { return null; }
        @Override public List<HttpHeader> headers() { return List.of(); }
        @Override public String httpVersion() { return "HTTP/1.1"; }
        @Override public int bodyOffset() { return 0; }
        @Override public ByteArray body() { return ByteArray.byteArray(new byte[0]); }
        @Override public String bodyToString() { return ""; }
        @Override public List<Marker> markers() { return List.of(); }
        @Override public boolean contains(String text, boolean caseInsensitive) { return false; }
        @Override public boolean contains(Pattern pattern) { return false; }
        @Override public ByteArray toByteArray() { return ByteArray.byteArray(new byte[0]); }
        @Override public String toString() { return "GET /test HTTP/1.1\r\n\r\n"; }
        @Override public HttpRequest copyToTempFile() { return this; }
        @Override public HttpRequest withService(burp.api.montoya.http.HttpService s) { return this; }
        @Override public HttpRequest withPath(String path) { return this; }
        @Override public HttpRequest withMethod(String method) { return this; }
        @Override public HttpRequest withHeader(HttpHeader h) { return this; }
        @Override public HttpRequest withHeader(String name, String value) { return this; }
        @Override public HttpRequest withParameter(burp.api.montoya.http.message.params.HttpParameter p) { return this; }
        @Override public HttpRequest withAddedParameters(List<? extends burp.api.montoya.http.message.params.HttpParameter> params) { return this; }
        @Override public HttpRequest withAddedParameters(burp.api.montoya.http.message.params.HttpParameter... params) { return this; }
        @Override public HttpRequest withRemovedParameters(List<? extends burp.api.montoya.http.message.params.HttpParameter> params) { return this; }
        @Override public HttpRequest withRemovedParameters(burp.api.montoya.http.message.params.HttpParameter... params) { return this; }
        @Override public HttpRequest withUpdatedParameters(List<? extends burp.api.montoya.http.message.params.HttpParameter> params) { return this; }
        @Override public HttpRequest withUpdatedParameters(burp.api.montoya.http.message.params.HttpParameter... params) { return this; }
        @Override public HttpRequest withTransformationApplied(burp.api.montoya.http.message.requests.HttpTransformation t) { return this; }
        @Override public HttpRequest withBody(String body) { return this; }
        @Override public HttpRequest withBody(ByteArray body) { return this; }
        @Override public HttpRequest withAddedHeader(String name, String value) { return this; }
        @Override public HttpRequest withAddedHeader(HttpHeader h) { return this; }
        @Override public HttpRequest withAddedHeaders(List<? extends HttpHeader> headers) { return this; }
        @Override public HttpRequest withAddedHeaders(HttpHeader... headers) { return this; }
        @Override public HttpRequest withUpdatedHeader(String name, String value) { return this; }
        @Override public HttpRequest withUpdatedHeader(HttpHeader h) { return this; }
        @Override public HttpRequest withUpdatedHeaders(List<? extends HttpHeader> headers) { return this; }
        @Override public HttpRequest withUpdatedHeaders(HttpHeader... headers) { return this; }
        @Override public HttpRequest withRemovedHeader(String name) { return this; }
        @Override public HttpRequest withRemovedHeader(HttpHeader h) { return this; }
        @Override public HttpRequest withRemovedHeaders(List<? extends HttpHeader> headers) { return this; }
        @Override public HttpRequest withRemovedHeaders(HttpHeader... headers) { return this; }
        @Override public HttpRequest withMarkers(List<Marker> markers) { return this; }
        @Override public HttpRequest withMarkers(Marker... markers) { return this; }
        @Override public HttpRequest withDefaultHeaders() { return this; }
    }
}
