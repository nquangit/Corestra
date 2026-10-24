package dev.nquangit.corestra.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveInjectionTest {
    private static InjectRule rule(InjectRule.Target target, String selector, List<String> transforms) {
        return new InjectRule("otp", "SMS code", true, target, selector, InjectRule.ValueSourceType.INTERACTIVE,
                "ignored", "captured-secret", InjectRule.WriteStrategy.OVERWRITE, transforms, null,
                new InteractiveInputConfig("Enter SMS code", true, 90));
    }

    private static InjectionService.MutableHttpRequest request() {
        return new InjectionService.MutableHttpRequest("POST /verify?otp=old&path=/login.php HTTP/1.1",
                List.of("Authorization: old", "Cookie: otp=old; sid=keep"), "otp=old");
    }

    @Test void inputThenTransformsApplyWithoutChangingTheDefinitionOrVariables() throws Exception {
        InjectRule rule = rule(InjectRule.Target.REQUEST_HEADER, "Authorization", List.of("trim", "prefix:Bearer "));
        Map<String, String> context = Map.of("auth.other", "untouched");
        AtomicInteger calls = new AtomicInteger();
        var service = new InjectionService((name, config) -> {
            calls.incrementAndGet(); assertEquals("SMS code", name); assertEquals(90, config.timeoutSeconds());
            return Optional.of("  code123  ");
        });
        var result = service.apply(List.of(rule), context, request());
        assertEquals("Authorization: Bearer code123", result.headers().getFirst());
        assertEquals(1, calls.get()); assertEquals(Map.of("auth.other", "untouched"), context);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(rule);
        assertFalse(json.contains("code123")); assertFalse(json.contains("captured-secret"));
        assertEquals(rule, mapper.readValue(json, InjectRule.class));
        assertTrue(rule.referencedVariableKeys().isEmpty());
    }

    @ParameterizedTest @EnumSource(value = InjectRule.Target.class, names = {"REQUEST_COOKIE", "REQUEST_QUERY_PARAMETER", "REQUEST_FORM_FIELD", "REQUEST_RAW_BODY_REPLACE", "REQUEST_RAW_BODY_APPEND"})
    void ordinaryInjectionTargetsAcceptInteractiveValues(InjectRule.Target target) {
        var result = new InjectionService((name, config) -> Optional.of("123456"))
                .apply(List.of(rule(target, target.name().contains("RAW") ? "" : "otp", List.of())), Map.of(), request());
        switch (target) {
            case REQUEST_COOKIE -> assertEquals("Cookie: otp=123456; sid=keep", result.headers().get(1));
            case REQUEST_QUERY_PARAMETER -> assertEquals("POST /verify?otp=123456&path=/login.php HTTP/1.1", result.requestLine());
            case REQUEST_FORM_FIELD -> assertEquals("otp=123456", result.body());
            case REQUEST_RAW_BODY_REPLACE -> assertEquals("123456", result.body());
            case REQUEST_RAW_BODY_APPEND -> assertEquals("otp=old123456", result.body());
            default -> fail("Unexpected target");
        }
    }

    @Test void jsonAndRegexTargetsUseTheSameValueSource() {
        var service = new InjectionService((name, config) -> Optional.of("otp$1\\x"));
        var json = new InjectionService.MutableHttpRequest("POST / HTTP/1.1", List.of(), "{\"otp\":\"old\"}");
        assertTrue(service.apply(List.of(rule(InjectRule.Target.REQUEST_JSON_PATH, "otp", List.of())), Map.of(), json)
                .body().contains("otp$1"));
        assertEquals("otp=otp$1\\x", service.apply(List.of(rule(InjectRule.Target.REQUEST_REGEX_REPLACEMENT,
                "old", List.of())), Map.of(), request()).body());
    }

    @Test void cancellationBlankInputAndUnavailableUiAbort() {
        InjectRule rule = rule(InjectRule.Target.REQUEST_FORM_FIELD, "otp", List.of());
        for (var value : List.of(Optional.<String>empty(), Optional.of("  "))) {
            assertThrows(InteractionAbortedException.class, () -> new InjectionService((name, config) -> value)
                    .apply(List.of(rule), Map.of(), request()));
        }
        assertThrows(InteractionAbortedException.class, () -> new InjectionService().apply(List.of(rule), Map.of(), request()));
        assertEquals("otp=old", request().body());
    }

    @Test void failedTransformInvalidRegexAndFailedJsonAbortEvenWithOptionalRules() {
        var service = new InjectionService((name, config) -> Optional.of("123456"));
        for (InjectRule rule : List.of(
                rule(InjectRule.Target.REQUEST_HEADER, "Authorization", List.of("substring:999")),
                rule(InjectRule.Target.REQUEST_REGEX_REPLACEMENT, "does-not-match", List.of()),
                rule(InjectRule.Target.REQUEST_REGEX_REPLACEMENT, "[", List.of()),
                rule(InjectRule.Target.REQUEST_JSON_PATH, "otp", List.of()))) {
            assertThrows(InteractionAbortedException.class, () -> service.apply(List.of(rule), Map.of(), request()));
        }
    }

    @Test void disabledRuleDoesNotAskAndLegacyRuleHasNoPrompt() throws Exception {
        InjectRule interactive = rule(InjectRule.Target.REQUEST_HEADER, "Authorization", List.of());
        InjectRule disabled = new InjectRule(interactive.id(), interactive.name(), false, interactive.target(),
                interactive.targetSelector(), interactive.valueSourceType(), "", "", interactive.writeStrategy(), List.of());
        var result = new InjectionService((name, config) -> { fail("Disabled rules must not prompt"); return Optional.empty(); })
                .apply(List.of(disabled), Map.of(), request());
        assertEquals(request().headers(), result.headers());
        String legacy = "{\"id\":\"old\",\"name\":\"Old\",\"enabled\":true,\"target\":\"REQUEST_HEADER\",\"targetSelector\":\"Authorization\",\"valueSourceType\":\"STATIC\",\"staticValue\":\"old\"}";
        assertNull(new ObjectMapper().readValue(legacy, InjectRule.class).interactiveInput());
        assertEquals(300, disabled.interactiveInput().timeoutSeconds());
    }

    @Test void timeoutIsBoundedAndInterruptionStopsBeforePrompt() {
        assertThrows(IllegalArgumentException.class, () -> new InteractiveInputConfig("OTP", true, -1));
        assertThrows(IllegalArgumentException.class, () -> new InteractiveInputConfig("OTP", false, 3601));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InteractionAbortedException.class, () -> new InjectionService((name, config) -> {
                fail("Interrupted execution must not ask"); return Optional.empty();
            }).apply(List.of(rule(InjectRule.Target.REQUEST_HEADER, "X-OTP", List.of())), Map.of(), request()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
