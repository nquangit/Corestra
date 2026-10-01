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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@ResourceLock("MontoyaObjectFactory")
class HttpRequestMutationAdapterTest {
    private MontoyaObjectFactory previousFactory;

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
