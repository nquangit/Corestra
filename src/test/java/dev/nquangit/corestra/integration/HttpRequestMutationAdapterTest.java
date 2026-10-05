package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.session.SessionAuthConfig;
import dev.nquangit.corestra.session.SessionGuard;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfileRepository;
import dev.nquangit.corestra.session.SessionSchedulerConfig;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import burp.api.montoya.http.message.responses.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ResourceLock("MontoyaObjectFactory")
class HttpRequestMutationAdapterTest {
    private MontoyaObjectFactory previousFactory;

    @Test
    void sessionInjectionForwardsOverrideWithTheMutatedRequestAndReleasesGuard() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(SessionProfile.general().withConfig("General", SessionScope.custom(List.of()),
                SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), List.of(new SessionInjectionRule(
                        "token", true, InjectRule.Target.REQUEST_HEADER, "Authorization", "Bearer actor",
                        InjectRule.WriteStrategy.OVERWRITE, List.of("PROXY"))), List.of()));
        ExtractionService extraction = new ExtractionService();
        HttpResponse response = proxy(HttpResponse.class, (p, method, args) -> null);
        SessionManager manager = new SessionManager(repository, List::of,
                new FlowEngine(extraction, new InjectionService(), request -> response), extraction, Clock.systemUTC());
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        AtomicReference<HttpSendMode> route = new AtomicReference<>();
        HttpClient delegate = new HttpClient() {
            @Override public HttpResponse send(HttpRequest request) { throw new AssertionError("Override dropped"); }
            @Override public HttpResponse send(HttpRequest request, HttpSendMode mode) {
                sent.set(request);
                route.set(mode);
                return response;
            }
        };
        SessionAwareHttpClient client = new SessionAwareHttpClient(delegate, () -> manager, new InjectionService());
        HttpRequest original = request(new RequestState("GET", "/target", "HTTP/2", "",
                List.of(header("Host", "example.test"))));
        assertSame(response, client.send(original, HttpSendMode.PROXY_PIPELINE));
        assertEquals("Bearer actor", sent.get().headerValue("Authorization"));
        assertEquals(HttpSendMode.PROXY_PIPELINE, route.get());
        assertEquals("HTTP/2", sent.get().httpVersion());
        assertFalse(SessionGuard.isActive());
    }

    @BeforeEach
    void installHeaderFactory() {
        previousFactory = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, method, args) -> {
            if (method.getName().equals("httpHeader") && args.length == 2
                    && args[0] instanceof String name && args[1] instanceof String value) {
                return header(name, value);
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @AfterEach
    void restoreFactory() {
        ObjectFactoryLocator.FACTORY = previousFactory;
    }

    @Test
    void nativeMutationPreservesVersionAndVirtualHostAndRefreshesContentLength() {
        HttpRequest original = request(new RequestState(
                "POST", "/upload", "HTTP/2", "abc",
                List.of(header("Host", "virtual.example"), header("Content-Length", "3"))));
        RequestMutationResult mutation = RequestMutationResult.of(
                "POST /upload HTTP/2",
                List.of("Host: virtual.example", "Content-Length: 3", "Authorization: Bearer actor-b"),
                "abcdef",
                List.of());

        HttpRequest updated = HttpRequestMutationAdapter.apply(original, mutation);

        assertEquals("HTTP/2", updated.httpVersion());
        assertEquals("virtual.example", updated.headerValue("Host"));
        assertEquals("Bearer actor-b", updated.headerValue("Authorization"));
        assertEquals("6", updated.headerValue("Content-Length"));
        assertEquals("abcdef", updated.bodyToString());
    }

    @Test
    void noOpMutationReturnsOriginalRequestSoUntouchedBodyBytesRemainUntouched() {
        HttpRequest original = request(new RequestState(
                "POST", "/binary", "HTTP/2", "binary\u0000body",
                List.of(header("Host", "virtual.example"), header("Content-Type", "application/octet-stream"))));
        RequestMutationResult mutation = RequestMutationResult.of(
                "POST /binary HTTP/2",
                List.of("Host: virtual.example", "Content-Type: application/octet-stream"),
                "binary\u0000body",
                List.of());

        assertSame(original, HttpRequestMutationAdapter.apply(original, mutation));
    }

    private static HttpRequest request(RequestState state) {
        return proxy(HttpRequest.class, (p, method, args) -> switch (method.getName()) {
            case "isInScope" -> true;
            case "httpService" -> proxy(burp.api.montoya.http.HttpService.class, (service, m, a) -> switch (m.getName()) {
                case "host" -> "example.test";
                case "port" -> 443;
                case "secure" -> true;
                default -> throw new UnsupportedOperationException(m.getName());
            });
            case "method" -> state.method();
            case "path" -> state.path();
            case "httpVersion" -> state.version();
            case "bodyToString" -> state.body();
            case "headers" -> state.headers();
            case "headerValue" -> state.headers().stream()
                    .filter(header -> header.name().equalsIgnoreCase((String) args[0]))
                    .map(HttpHeader::value)
                    .findFirst().orElse(null);
            case "withMethod" -> request(state.withMethod((String) args[0]));
            case "withPath" -> request(state.withPath((String) args[0]));
            case "withRemovedHeaders" -> request(state.withHeaders(List.of()));
            case "withAddedHeaders" -> request(state.withAddedHeaders(castHeaders(args[0])));
            case "withBody" -> request(state.withBody((String) args[0]));
            case "toString" -> state.method() + " " + state.path() + " " + state.version();
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static List<HttpHeader> castHeaders(Object value) {
        return (List<HttpHeader>) value;
    }

    private static HttpHeader header(String name, String value) {
        return proxy(HttpHeader.class, (p, method, args) -> switch (method.getName()) {
            case "name" -> name;
            case "value" -> value;
            case "toString" -> name + ": " + value;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private record RequestState(
            String method, String path, String version, String body, List<HttpHeader> headers) {
        private RequestState withMethod(String value) {
            return new RequestState(value, path, version, body, headers);
        }

        private RequestState withPath(String value) {
            return new RequestState(method, value, version, body, headers);
        }

        private RequestState withHeaders(List<HttpHeader> values) {
            return new RequestState(method, path, version, body, List.copyOf(values));
        }

        private RequestState withAddedHeaders(List<HttpHeader> values) {
            ArrayList<HttpHeader> combined = new ArrayList<>(headers);
            combined.addAll(values);
            return withHeaders(combined);
        }

        private RequestState withBody(String value) {
            ArrayList<HttpHeader> updated = new ArrayList<>(headers);
            updated.removeIf(header -> header.name().equalsIgnoreCase("Content-Length"));
            updated.add(header("Content-Length", Integer.toString(value.length())));
            return new RequestState(method, path, version, value, List.copyOf(updated));
        }
    }
}
