package dev.nquangit.corestra.integration;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.session.SessionCriterionType;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionScopeCriterion;
import dev.nquangit.corestra.session.SessionScopeMode;
import dev.nquangit.corestra.session.SessionScopeRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTrafficContextsTest {
    @Test
    void carriesActualServicePortProtocolAndUserHeaderAndRemovesQueryFromPath() {
        HttpRequest request = request(service(true), "/api/orders?user=other");
        var context = SessionTrafficContexts.fromRequest(request, "SCANNER");
        assertEquals("SCANNER", context.toolTypeName());
        assertEquals("api.example", context.host());
        assertEquals(8443, context.port());
        assertEquals("https", context.scheme());
        assertEquals("/api/orders", context.path());
        var scope = new SessionScope(SessionScopeMode.CUSTOM, List.of(), List.of(new SessionScopeRule("API", List.of(
                new SessionScopeCriterion(SessionCriterionType.HOST, "", "^api\\.example$"),
                new SessionScopeCriterion(SessionCriterionType.PORT, "", "^8443$"),
                new SessionScopeCriterion(SessionCriterionType.PROTOCOL, "", "^https$"),
                new SessionScopeCriterion(SessionCriterionType.HEADER, "X-User", "^Alice$")))));
        assertTrue(scope.matches(context));
    }

    @Test
    void unavailableMetadataIsNotInferredFromHeadersOrDefaultPorts() {
        HttpService service = service(false);
        var context = SessionTrafficContexts.fromRequest(request(service, "/api?x=1"), "PROXY");
        assertEquals(0, context.port());
        assertEquals("", context.scheme());
        assertFalse(new SessionScopeCriterion(SessionCriterionType.PORT, "", "^443$").matches(context));
        assertFalse(new SessionScopeCriterion(SessionCriterionType.PROTOCOL, "", "^https$").matches(context));
    }

    private static HttpService service(boolean metadata) {
        return (HttpService) Proxy.newProxyInstance(HttpService.class.getClassLoader(), new Class<?>[]{HttpService.class},
                (object, method, args) -> switch (method.getName()) {
                    case "host" -> "api.example";
                    case "port" -> {
                        if (!metadata) throw new UnsupportedOperationException();
                        yield 8443;
                    }
                    case "secure" -> {
                        if (!metadata) throw new UnsupportedOperationException();
                        yield true;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static HttpRequest request(HttpService service, String path) {
        HttpHeader header = (HttpHeader) Proxy.newProxyInstance(HttpHeader.class.getClassLoader(), new Class<?>[]{HttpHeader.class},
                (object, method, args) -> switch (method.getName()) {
                    case "name" -> "X-User";
                    case "value" -> "Alice";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (HttpRequest) Proxy.newProxyInstance(HttpRequest.class.getClassLoader(), new Class<?>[]{HttpRequest.class},
                (object, method, args) -> switch (method.getName()) {
                    case "httpService" -> service;
                    case "method" -> "GET";
                    case "path" -> path;
                    case "headers" -> List.of(header);
                    case "bodyToString" -> "";
                    case "isInScope" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
