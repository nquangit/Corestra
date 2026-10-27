package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;
import dev.nquangit.corestra.integration.ManualTestMessages;
import dev.nquangit.corestra.persistence.HttpSendMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class StepExecutorContentLengthTest {
    private MontoyaObjectFactory previous;
    private final StepExecutor executor = new StepExecutor(new ExtractionService(), new InjectionService());

    @BeforeEach void setup() {
        previous = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = ManualTestMessages.factory();
    }

    @AfterEach void restore() { ObjectFactoryLocator.FACTORY = previous; }

    @ParameterizedTest
    @ValueSource(strings = {"a", "longer-than-the-captured-body", "đăng nhập 🔑", ""})
    void rawBodyInjectionReframesRequestAndRecordsActualHeaders(String value) {
        var rule = inject(InjectRule.Target.REQUEST_RAW_BODY_REPLACE, "");
        var template = template("POST", List.of("Content-Length: 9", "cOnTeNt-LeNgTh: 999"), "old-value");
        var record = run(template, List.of(rule), value, null);
        assertTrue(record.success(), record.errorMessage());
        var recorded = ManualTestMessages.parse(ManualTestMessages.service("api.test", 8443, true), record.renderedRequest());
        assertFraming(recorded);
        assertEquals(value, recorded.bodyToString());
        assertEquals(recorded.headerValue("Content-Length"), record.extractedVariables().get("sentLength"));
        assertEquals("9", template.headerLines().getFirst().substring("Content-Length: ".length()));
        assertEquals("old-value", template.body());
    }

    @Test void jsonAndFormInjectionUseTheFinalSerializedBodyLength() {
        for (var target : List.of(InjectRule.Target.REQUEST_JSON_PATH, InjectRule.Target.REQUEST_FORM_FIELD)) {
            boolean json = target == InjectRule.Target.REQUEST_JSON_PATH;
            var template = template("POST", List.of("Content-Length: 1", "Content-Type: " +
                    (json ? "application/json" : "application/x-www-form-urlencoded")), json ? "{\"username\":\"old\"}" : "username=old");
            var record = run(template, List.of(inject(target, "username")), "alice@example.test", null);
            assertTrue(record.success(), record.errorMessage());
            assertTrue(record.renderedRequest().contains(json ? "alice@example.test" : "alice%40example.test"));
        }
    }

    @ParameterizedTest
    @EnumSource(HttpSendMode.class)
    void framingAndStepRouteArePreservedForBothTransportChoices(HttpSendMode mode) {
        var template = template("POST", List.of("Content-Length: 999", "Host: virtual.test", "X-Test: kept"), "hello");
        var record = run(template, List.of(), "", mode);
        assertTrue(record.success(), record.errorMessage());
        assertTrue(record.renderedRequest().contains("Host: virtual.test\r\n"));
        assertTrue(record.renderedRequest().contains("X-Test: kept\r\n"));
    }

    @Test void emptyRequestWithoutExplicitLengthStaysUnframed() {
        var built = executor.buildMontoyaRequest(template("GET", List.of(), ""), "GET / HTTP/1.1", List.of(), "");
        assertFalse(built.hasHeader("Content-Length"));
        assertEquals("", built.bodyToString());
    }

    @Test void nonemptyBodyWithoutCapturedLengthGetsFramed() {
        var record = run(template("POST", List.of(), "hello"), List.of(), "", null);
        assertTrue(record.success(), record.errorMessage());
        assertTrue(record.renderedRequest().contains("Content-Length: 5\r\n"));
    }

    private StepExecutionRecord run(StoredRequestTemplate template, List<InjectRule> rules, String value, HttpSendMode mode) {
        var extraction = new ExtractRule("length", "Sent length", true, ExtractRule.SourceSide.REQUEST,
                ExtractRule.ExtractorType.HEADER, "Content-Length", "sentLength", List.of());
        var step = new FlowStep("step", "Step", true, "", template, List.of(extraction), rules, List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW).withSendModeOverride(mode);
        var sent = new ArrayList<HttpRequest>();
        HttpClient client = new HttpClient() {
            @Override public HttpResponse send(HttpRequest request) { throw new AssertionError("Route overload must be used"); }
            @Override public HttpResponse send(HttpRequest request, HttpSendMode override) {
                assertEquals(mode, override);
                assertEquals("api.test", request.httpService().host());
                assertEquals(8443, request.httpService().port());
                assertTrue(request.httpService().secure());
                assertEquals("/login?path=/original", request.path());
                assertFraming(request);
                sent.add(request);
                return ManualTestMessages.response("ok");
            }
        };
        var record = executor.execute(step, ExecutionContext.start("run", "flow", Map.of(), 0).withVariable("value", value), client);
        assertEquals(1, sent.size(), record.errorMessage());
        assertEquals(sent.getFirst().toString(), record.renderedRequest());
        return record;
    }

    private static void assertFraming(HttpRequest request) {
        assertEquals(1, request.headers().stream().filter(h -> h.name().equalsIgnoreCase("Content-Length")).count());
        assertEquals(Integer.toString(request.body().getBytes().length), request.headerValue("Content-Length"));
    }

    private static StoredRequestTemplate template(String method, List<String> headers, String body) {
        return new StoredRequestTemplate(method, "https", "api.test", 8443, "/login?path=/original", headers, body);
    }

    private static InjectRule inject(InjectRule.Target target, String selector) {
        return new InjectRule("inject", "Value", true, target, selector, InjectRule.ValueSourceType.VARIABLE,
                "value", "", InjectRule.WriteStrategy.OVERWRITE);
    }
}
