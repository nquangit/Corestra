package dev.nquangit.corestra.integration;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ai.Ai;
import burp.api.montoya.bambda.Bambda;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.collaborator.Collaborator;
import burp.api.montoya.comparer.Comparer;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.Marker;
import burp.api.montoya.core.Registration;
import burp.api.montoya.decoder.Decoder;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.TimingData;
import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.Cookie;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.responses.analysis.ResponseKeywordsAnalyzer;
import burp.api.montoya.http.message.responses.analysis.ResponseVariationsAnalyzer;
import burp.api.montoya.http.sessions.CookieJar;
import burp.api.montoya.http.sessions.SessionHandlingAction;
import burp.api.montoya.intruder.Intruder;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.organizer.Organizer;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.project.Project;
import burp.api.montoya.proxy.Proxy;
import burp.api.montoya.repeater.Repeater;
import burp.api.montoya.scanner.Scanner;
import burp.api.montoya.scope.Scope;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.utilities.Utilities;
import burp.api.montoya.websocket.WebSockets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MontoyaHttpClientTest {

    // ---------------------------------------------------------------------------
    // Constructor guard
    // ---------------------------------------------------------------------------

    @Test
    void constructor_rejectsNullApi() {
        assertThrows(NullPointerException.class, () -> new MontoyaHttpClient(null));
    }

    // ---------------------------------------------------------------------------
    // Null-response path
    // ---------------------------------------------------------------------------

    @Test
    void send_throwsIllegalStateException_whenTransportReturnsNullRequestResponse() {
        // Http stub: sendRequest(HttpRequest) returns null
        Http nullRrHttp = new StubHttp((HttpRequestResponse) null);
        MontoyaApi api = new StubMontoyaApi(nullRrHttp);
        MontoyaHttpClient client = new MontoyaHttpClient(api);

        assertThrows(IllegalStateException.class, () -> client.send(null));
    }

    @Test
    void send_throwsClearIllegalStateException_whenHttpApiIsUnavailable() {
        MontoyaApi api = new StubMontoyaApi(null);
        MontoyaHttpClient client = new MontoyaHttpClient(api);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.send(null));

        assertTrue(exception.getMessage().contains("Montoya HTTP API is unavailable"));
    }

    @Test
    void send_wrapsRuntimeFailureFromMontoyaSendRequest() {
        RuntimeException failure = new NullPointerException("burp delegate");
        MontoyaApi api = new StubMontoyaApi(new StubHttp(failure));
        MontoyaHttpClient client = new MontoyaHttpClient(api);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.send(null));

        assertTrue(exception.getMessage().contains("Montoya HTTP send failed"));
        assertSame(failure, exception.getCause());
    }

    @Test
    void send_throwsIllegalStateException_whenResponseIsNull() {
        // HttpRequestResponse stub: response() returns null
        HttpRequestResponse nullResponseRR = new StubHttpRequestResponse(null);
        Http http = new StubHttp(nullResponseRR);
        MontoyaApi api = new StubMontoyaApi(http);
        MontoyaHttpClient client = new MontoyaHttpClient(api);

        assertThrows(IllegalStateException.class, () -> client.send(null));
    }

    // ---------------------------------------------------------------------------
    // Minimal stubs
    // ---------------------------------------------------------------------------

    /** Stub MontoyaApi that returns the provided Http and throws for everything else. */
    private static final class StubMontoyaApi implements MontoyaApi {
        private final Http http;

        StubMontoyaApi(Http http) { this.http = http; }

        @Override public Http http() { return http; }

        @Override public Ai ai()                   { throw new UnsupportedOperationException(); }
        @Override public Bambda bambda()           { throw new UnsupportedOperationException(); }
        @Override public BurpSuite burpSuite()     { throw new UnsupportedOperationException(); }
        @Override public Collaborator collaborator(){ throw new UnsupportedOperationException(); }
        @Override public Comparer comparer()       { throw new UnsupportedOperationException(); }
        @Override public Decoder decoder()         { throw new UnsupportedOperationException(); }
        @Override public Extension extension()     { throw new UnsupportedOperationException(); }
        @Override public Intruder intruder()       { throw new UnsupportedOperationException(); }
        @Override public Logging logging()         { throw new UnsupportedOperationException(); }
        @Override public Organizer organizer()     { throw new UnsupportedOperationException(); }
        @Override public Persistence persistence() { throw new UnsupportedOperationException(); }
        @Override public Project project()         { throw new UnsupportedOperationException(); }
        @Override public Proxy proxy()             { throw new UnsupportedOperationException(); }
        @Override public Repeater repeater()       { throw new UnsupportedOperationException(); }
        @Override public Scanner scanner()         { throw new UnsupportedOperationException(); }
        @Override public Scope scope()             { throw new UnsupportedOperationException(); }
        @Override public SiteMap siteMap()         { throw new UnsupportedOperationException(); }
        @Override public UserInterface userInterface() { throw new UnsupportedOperationException(); }
        @Override public Utilities utilities()     { throw new UnsupportedOperationException(); }
        @Override public WebSockets websockets()   { throw new UnsupportedOperationException(); }
    }

    /** Stub Http that returns a fixed HttpRequestResponse from sendRequest(HttpRequest). */
    private static final class StubHttp implements Http {
        private final HttpRequestResponse fixedResult;
        private final RuntimeException failure;

        StubHttp(HttpRequestResponse fixedResult) {
            this.fixedResult = fixedResult;
            this.failure = null;
        }

        StubHttp(RuntimeException failure) {
            this.fixedResult = null;
            this.failure = failure;
        }

        @Override
        public HttpRequestResponse sendRequest(HttpRequest request) {
            if (failure != null) {
                throw failure;
            }
            return fixedResult;
        }

        @Override
        public HttpRequestResponse sendRequest(HttpRequest request, HttpMode mode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpRequestResponse sendRequest(HttpRequest request, HttpMode mode, String connectionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpRequestResponse sendRequest(HttpRequest request, RequestOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HttpRequestResponse> sendRequests(List<HttpRequest> requests) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HttpRequestResponse> sendRequests(List<HttpRequest> requests, HttpMode mode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration registerHttpHandler(HttpHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration registerSessionHandlingAction(SessionHandlingAction action) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseKeywordsAnalyzer createResponseKeywordsAnalyzer(List<String> keywords) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseVariationsAnalyzer createResponseVariationsAnalyzer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CookieJar cookieJar() { throw new UnsupportedOperationException(); }
    }

    /** Stub HttpRequestResponse whose response() returns the provided value (possibly null). */
    private static final class StubHttpRequestResponse implements HttpRequestResponse {
        private final HttpResponse response;

        StubHttpRequestResponse(HttpResponse response) { this.response = response; }

        @Override public HttpResponse response()       { return response; }
        @Override public HttpRequest request()         { throw new UnsupportedOperationException(); }
        @Override public burp.api.montoya.http.HttpService httpService() { throw new UnsupportedOperationException(); }
        @Override public Annotations annotations()     { throw new UnsupportedOperationException(); }
        @Override public Optional<TimingData> timingData() { throw new UnsupportedOperationException(); }
        @Override public String url()                  { throw new UnsupportedOperationException(); }
        @Override public boolean hasResponse()         { throw new UnsupportedOperationException(); }
        @Override public ContentType contentType()     { throw new UnsupportedOperationException(); }
        @Override public short statusCode()            { throw new UnsupportedOperationException(); }
        @Override public List<Marker> requestMarkers() { throw new UnsupportedOperationException(); }
        @Override public List<Marker> responseMarkers(){ throw new UnsupportedOperationException(); }
        @Override public boolean contains(String s, boolean b) { throw new UnsupportedOperationException(); }
        @Override public boolean contains(Pattern p)   { throw new UnsupportedOperationException(); }
        @Override public HttpRequestResponse copyToTempFile() { throw new UnsupportedOperationException(); }
        @Override public HttpRequestResponse withAnnotations(Annotations a) { throw new UnsupportedOperationException(); }
        @Override public HttpRequestResponse withRequestMarkers(List<Marker> m) { throw new UnsupportedOperationException(); }
        @Override public HttpRequestResponse withRequestMarkers(Marker... m) { throw new UnsupportedOperationException(); }
        @Override public HttpRequestResponse withResponseMarkers(List<Marker> m) { throw new UnsupportedOperationException(); }
        @Override public HttpRequestResponse withResponseMarkers(Marker... m) { throw new UnsupportedOperationException(); }
    }
}
