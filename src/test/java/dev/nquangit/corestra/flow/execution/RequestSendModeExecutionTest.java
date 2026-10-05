package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.ActorIsolatedHttpClient;
import dev.nquangit.corestra.integration.HttpClient;
import dev.nquangit.corestra.integration.RoutingHttpClient;
import dev.nquangit.corestra.integration.SessionAwareHttpClient;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectSettings;
import dev.nquangit.corestra.session.SessionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestSendModeExecutionTest {
    @Test
    void mixedStepRoutesSurviveNormalAndActorIsolatedTransportAndFollowChangingProjectDefault() {
        for (boolean actorIsolated : List.of(false, true)) {
            List<HttpSendMode> sent = new ArrayList<>();
            AtomicReference<ProjectSettings> settings = new AtomicReference<>(
                    new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "127.0.0.1", 8080));
            RoutingHttpClient router = new RoutingHttpClient(settings::get,
                    request -> { sent.add(HttpSendMode.STANDARD); return response(); },
                    request -> { sent.add(HttpSendMode.PROXY_PIPELINE); return response(); });
            HttpClient transport = actorIsolated ? new ActorIsolatedHttpClient(router)
                    : new SessionAwareHttpClient(router, () -> null, new InjectionService());
            FlowEngine engine = TestFlowEngineFactory.withHttpClient(transport);
            FlowDefinition flow = new FlowDefinition("flow", "Mixed", "", true, List.of(
                    step("standard").withSendModeOverride(HttpSendMode.STANDARD),
                    step("proxy").withSendModeOverride(HttpSendMode.PROXY_PIPELINE), step("inherit")), null, null, null);
            assertTrue(engine.execute(flow, ExecutionContext.start("run", flow.id(), java.util.Map.of(), 1000L)).success());
            assertEquals(List.of(HttpSendMode.STANDARD, HttpSendMode.PROXY_PIPELINE, HttpSendMode.PROXY_PIPELINE), sent);
            settings.set(ProjectSettings.defaults());
            sent.clear();
            assertTrue(engine.execute(flow, ExecutionContext.start("run", flow.id(), java.util.Map.of(), 1000L)).success());
            assertEquals(List.of(HttpSendMode.STANDARD, HttpSendMode.PROXY_PIPELINE, HttpSendMode.STANDARD), sent);
        }
    }

    @Test
    void authGuardStillPreservesStepOverride() {
        List<HttpSendMode> sent = new ArrayList<>();
        RoutingHttpClient router = new RoutingHttpClient(ProjectSettings::defaults,
                request -> { sent.add(HttpSendMode.STANDARD); return response(); },
                request -> { sent.add(HttpSendMode.PROXY_PIPELINE); return response(); });
        HttpClient transport = new SessionAwareHttpClient(router, () -> { throw new AssertionError("Guard bypasses session work"); },
                new InjectionService());
        SessionGuard.enter();
        try {
            FlowDefinition flow = new FlowDefinition("auth", "Auth", "", true,
                    List.of(step("login").withSendModeOverride(HttpSendMode.PROXY_PIPELINE)), null, null, null);
            assertTrue(TestFlowEngineFactory.withHttpClient(transport).execute(flow, ExecutionContext.start("run", flow.id(), java.util.Map.of(), 1000L)).success());
            assertEquals(List.of(HttpSendMode.PROXY_PIPELINE), sent);
        } finally {
            SessionGuard.exit();
        }
    }

    private static FlowStep step(String id) {
        return new FlowStep(id, id, true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static HttpResponse response() {
        return (HttpResponse) Proxy.newProxyInstance(HttpResponse.class.getClassLoader(), new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 200;
                    case "httpVersion" -> "HTTP/1.1";
                    case "reasonPhrase" -> "OK";
                    case "headers" -> List.of();
                    case "bodyToString" -> "";
                    case "toString" -> "HTTP/1.1 200 OK\r\n\r\n";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
