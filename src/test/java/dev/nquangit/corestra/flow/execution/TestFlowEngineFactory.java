package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Marker;
import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.params.HttpParameter;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.requests.HttpTransformation;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;
import dev.nquangit.corestra.integration.ManualTestMessages;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Package-private factory for creating test-friendly {@link FlowEngine} instances.
 *
 * <p>This class lives in the {@code flow.execution} package so it can access the
 * package-private {@code FlowEngine(StepExecutor, HttpClient)} constructor and the
 * package-private {@link StepExecutor#buildMontoyaRequest} method. Tests in other
 * packages can call {@link #withHttpClient(HttpClient)} to get a fully wired
 * {@code FlowEngine} without needing a real Burp runtime.
 */
public final class TestFlowEngineFactory {

    private TestFlowEngineFactory() {}

    /**
     * Creates a {@link FlowEngine} backed by the supplied {@code HttpClient}.
     *
     * <p>The {@link StepExecutor} is subclassed to override {@code buildMontoyaRequest}
     * so that no real Montoya {@code HttpRequest} factory is invoked.
     *
     * @param httpClient the HTTP client to use for sends
     * @return a fully wired engine suitable for unit tests
     */
    public static FlowEngine withHttpClient(HttpClient httpClient) {
        ExtractionService extractionService = new ExtractionService();
        InjectionService injectionService = new InjectionService();
        StepExecutor testStepExecutor = new StepExecutor(extractionService, injectionService) {
            @Override
            HttpRequest buildMontoyaRequest(
                    StoredRequestTemplate template,
                    String requestLine,
                    List<String> headers,
                    String body) {
                return storedRequest(template, requestLine, headers, body);
            }
        };
        return new FlowEngine(testStepExecutor, httpClient);
    }

    /** Provides a faithful native snapshot for tests that bypass the production Montoya bridge. */
    static HttpRequest storedRequest(StoredRequestTemplate template, String line, List<String> headers, String body) {
        return ManualTestMessages.parse(ManualTestMessages.service(template.host(), template.port(),
                "https".equalsIgnoreCase(template.scheme())), line + "\r\n"
                + String.join("\r\n", headers) + (headers.isEmpty() ? "\r\n" : "\r\n\r\n") + body);
    }

    /** Minimal stub that satisfies the {@link HttpRequest} interface without Burp. */
    static final class StubHttpRequest implements HttpRequest {
        @Override public boolean isInScope() { throw new UnsupportedOperationException(); }
        @Override public burp.api.montoya.http.HttpService httpService() { throw new UnsupportedOperationException(); }
        @Override public String url() { throw new UnsupportedOperationException(); }
        @Override public String method() { return "GET"; }
        @Override public String path() { return "/test"; }
        @Override public String query() { return ""; }
        @Override public String pathWithoutQuery() { return "/test"; }
        @Override public String fileExtension() { return ""; }
        @Override public ContentType contentType() { throw new UnsupportedOperationException(); }
        @Override public List<ParsedHttpParameter> parameters() { return List.of(); }
        @Override public List<ParsedHttpParameter> parameters(HttpParameterType t) { return List.of(); }
        @Override public boolean hasParameters() { return false; }
        @Override public boolean hasParameters(HttpParameterType t) { return false; }
        @Override public ParsedHttpParameter parameter(String name, HttpParameterType t) { return null; }
        @Override public String parameterValue(String name, HttpParameterType t) { return null; }
        @Override public ParsedHttpParameter parameter(String name) { return null; }
        @Override public String parameterValue(String name) { return null; }
        @Override public boolean hasParameter(String name, HttpParameterType t) { return false; }
        @Override public boolean hasParameter(HttpParameter p) { return false; }
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
        @Override public HttpRequest withParameter(HttpParameter p) { return this; }
        @Override public HttpRequest withAddedParameters(List<? extends HttpParameter> params) { return this; }
        @Override public HttpRequest withAddedParameters(HttpParameter... params) { return this; }
        @Override public HttpRequest withRemovedParameters(List<? extends HttpParameter> params) { return this; }
        @Override public HttpRequest withRemovedParameters(HttpParameter... params) { return this; }
        @Override public HttpRequest withUpdatedParameters(List<? extends HttpParameter> params) { return this; }
        @Override public HttpRequest withUpdatedParameters(HttpParameter... params) { return this; }
        @Override public HttpRequest withTransformationApplied(HttpTransformation t) { return this; }
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
        @Override public HttpRequest withMarkers(Marker... markers) { return this; }
        @Override public HttpRequest withMarkers(List<Marker> markers) { return this; }
        @Override public HttpRequest withDefaultHeaders() { return this; }
    }
}
