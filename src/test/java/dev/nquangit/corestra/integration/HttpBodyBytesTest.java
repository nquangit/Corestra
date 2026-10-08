package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.HttpMessage;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

class HttpBodyBytesTest {
    @Test
    void textDisabledAndOppositeSideRulesNeverReadBinaryBody() {
        HttpMessage message = (HttpMessage) Proxy.newProxyInstance(HttpMessage.class.getClassLoader(),
                new Class<?>[]{HttpMessage.class}, (object, method, arguments) -> {
                    throw new AssertionError("No body API should be called: " + method.getName());
                });
        assertNull(HttpBodyBytes.readForExtraction(message, List.of(), ExtractRule.SourceSide.RESPONSE));
        assertNull(HttpBodyBytes.readForExtraction(message, List.of(rule(true, ExtractRule.ExtractorType.BODY_TEXT)),
                ExtractRule.SourceSide.RESPONSE));
        assertNull(HttpBodyBytes.readForExtraction(message, List.of(rule(false, ExtractRule.ExtractorType.BODY_RAW)),
                ExtractRule.SourceSide.RESPONSE));
        assertNull(HttpBodyBytes.readForExtraction(message, List.of(rule(true, ExtractRule.ExtractorType.BODY_RAW)),
                ExtractRule.SourceSide.REQUEST));
    }

    @Test
    void textOnlyAdapterReturnsUnavailableBytesWithoutTryingBodyText() {
        HttpMessage message = (HttpMessage) Proxy.newProxyInstance(HttpMessage.class.getClassLoader(),
                new Class<?>[]{HttpMessage.class}, (object, method, arguments) -> {
                    if (method.getName().equals("body")) throw new UnsupportedOperationException();
                    throw new AssertionError("Must not use a text fallback: " + method.getName());
                });
        assertNull(HttpBodyBytes.readForExtraction(message, List.of(rule(true, ExtractRule.ExtractorType.BODY_RAW)),
                ExtractRule.SourceSide.RESPONSE));
    }

    private static ExtractRule rule(boolean enabled, ExtractRule.ExtractorType type) {
        return new ExtractRule("body", "Body", enabled, ExtractRule.SourceSide.RESPONSE, type,
                "", "image", List.of());
    }
}
