package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TriggerInputServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    @Test void noneNeedsNoRepositoryAndNoProfilesWhenFlowHasNoExternalInputs() {
        var resolver = new TriggerInputService(() -> { throw new AssertionError("Profiles must not be read"); }, CLOCK);
        var variables = resolver.resolve(TriggerInputBindings.none());
        assertTrue(variables.isEmpty());
        assertDoesNotThrow(() -> TriggerInputService.requireInputs(flow(step("one")), 0, 0, variables));
    }

    @Test void selectedProfilesAreCopiedOnceAndNamespacesDoNotLeakOrSelectTheDataOwner() {
        var actor = actor(true, 0);
        var ownerData = DataProfile.enabled("data", "Other actor's data", "other-actor", Map.of("id", "42"));
        var reads = new AtomicInteger();
        var resolver = new TriggerInputService(() -> {
            reads.incrementAndGet();
            return new AccessWorkspace(Map.of("actor", actor), Map.of("data", ownerData), Map.of(), Map.of(), Map.of());
        }, CLOCK);
        var variables = resolver.resolve(new TriggerInputBindings("actor", "data"));
        assertEquals(1, reads.get());
        assertEquals("alice", variables.get("credentials.username"));
        assertEquals("alice", variables.get("username"));
        assertEquals("access", variables.get("auth.token"));
        assertEquals("bearer", variables.get("auth.header.Authorization"));
        assertEquals("cookie", variables.get("auth.cookie.JSESSIONID"));
        assertEquals("42", variables.get("data.id"));
        assertFalse(variables.containsKey("id"));
        assertThrows(UnsupportedOperationException.class, () -> variables.put("x", "y"));
        assertEquals("access", actor.state().variables().get("token"));
    }

    @Test void eitherProfileCanBeNoneAndExpiredAuthDoesNotBlockCredentialsForLogin() {
        var resolver = resolver(actor(true, 1000), DataProfile.enabled("data", "Data", "", Map.of("id", "1")));
        var credentials = resolver.resolve(new TriggerInputBindings("actor", ""));
        assertEquals("alice", credentials.get("credentials.username"));
        assertFalse(credentials.containsKey("auth.token"));
        assertFalse(credentials.containsKey("auth.cookie.JSESSIONID"));
        assertFalse(credentials.containsKey("token"));
        assertEquals(Map.of("data.id", "1"), resolver.resolve(new TriggerInputBindings("", "data")));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(
                flow(step("refresh", "auth.token")), 0, 0, credentials));
    }

    @Test void missingAndDisabledReferencesAreNeverSilentlyTreatedAsNoneEvenIfUnused() {
        var resolver = resolver(actor(false, 0), new DataProfile("data", "Data", "", Map.of(), false));
        for (var bindings : List.of(new TriggerInputBindings("missing", ""), new TriggerInputBindings("actor", ""),
                new TriggerInputBindings("", "missing"), new TriggerInputBindings("", "data"))) {
            assertThrows(TriggerInputException.class, () -> resolver.resolve(bindings));
        }
    }

    @Test void noneMissingKeyAndBlankAreReportedWithoutSecretValues() {
        var flow = flow(step("login", "credentials.username", "credentials.password", "data.id"));
        for (var values : List.of(Map.<String, String>of(), Map.of("credentials.username", "private-user", "credentials.password", " "))) {
            var failure = assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(flow, 0, 0, values));
            assertTrue(failure.getMessage().contains("credentials.password"));
            assertTrue(failure.getMessage().contains("data.id"));
            assertFalse(failure.getMessage().contains("private-user"));
        }
    }

    @Test void orderedExtractsSatisfyLaterInputsIncludingAuthAliasesWithoutAnyProfile() {
        var first = step("first").withExtractRules(List.of(extract("token", ExtractRule.SourceSide.RESPONSE)));
        var target = step("target", "auth.token");
        var flow = flow(first, target, step("unused", "data.notNeeded"));
        assertDoesNotThrow(() -> TriggerInputService.requireInputs(flow, 1, 1, Map.of()));
        assertTrue(TriggerInputService.analyze(flow, 1, 1, Map.of()).getFirst().source().contains("first"));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(flow, 1, 2, Map.of()));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(flow(target, first), 0, 1, Map.of()));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(flow(first.withEnabled(false), target), 1, 1, Map.of()));
    }

    @Test void onlyLiveTargetsMayUseTheirOwnRequestExtractionBeforeInjection() {
        var request = step("live", "auth.token").withExtractRules(List.of(extract("token", ExtractRule.SourceSide.REQUEST)));
        assertDoesNotThrow(() -> TriggerInputService.requireInputs(flow(request), 0, 0, Map.of()));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(flow(request, step("target")), 1, 1, Map.of()));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(
                flow(request.withExtractRules(List.of(extract("token", ExtractRule.SourceSide.RESPONSE)))), 0, 0, Map.of()));
    }

    @Test void disabledRulesDoNotRequireInputsAndTemplateReferencesAreValidated() {
        var disabled = new InjectRule("disabled", "Disabled", false, InjectRule.Target.REQUEST_HEADER, "X-Test",
                InjectRule.ValueSourceType.VARIABLE, "data.absent", "", null);
        var template = new InjectRule("template", "Bearer", true, InjectRule.Target.REQUEST_HEADER, "Authorization",
                InjectRule.ValueSourceType.TEMPLATE, "", "Bearer {{auth.token}}", null);
        assertDoesNotThrow(() -> TriggerInputService.requireInputs(flow(step("x").withInjectRules(List.of(disabled))), 0, 0, Map.of()));
        assertThrows(TriggerInputException.class, () -> TriggerInputService.requireInputs(flow(step("x").withInjectRules(List.of(template))), 0, 0, Map.of()));
    }

    @Test void exclusionsOnlyChangeTheLiveTargetsRunCopyAndStaleReferencesAreRejected() {
        var target = step("target", "data.id");
        var id = target.injectRules().getFirst().id();
        var before = step("before").withInjectRules(target.injectRules());
        var original = flow(before, target, step("after"));
        var bindings = new TriggerInputBindings("", "", List.of(id));
        var effective = TriggerInputService.applyTargetExclusions(original, 1, bindings);
        assertTrue(effective.steps().get(1).injectRules().isEmpty());
        assertEquals(target.injectRules(), original.steps().get(1).injectRules());
        assertEquals(before.injectRules(), effective.steps().getFirst().injectRules());
        assertEquals(original.steps().getLast(), effective.steps().getLast());
        assertThrows(TriggerInputException.class, () -> TriggerInputService.applyTargetExclusions(original, 1,
                new TriggerInputBindings("", "", List.of("deleted"))));
        assertThrows(IllegalArgumentException.class, () -> new TriggerInputBindings("", "", List.of(" ")));
    }

    private static TriggerInputService resolver(AuthProfile actor, DataProfile data) {
        return new TriggerInputService(() -> new AccessWorkspace(Map.of(actor.id(), actor), Map.of(data.id(), data),
                Map.of(), Map.of(), Map.of()), CLOCK);
    }
    private static AuthProfile actor(boolean enabled, long expiry) {
        return new AuthProfile("actor", "Alice", "", "manual", Map.of("username", "alice"),
                new AuthState(Map.of("token", "access"), Map.of("Authorization", "bearer"), Map.of("JSESSIONID", "cookie"), expiry, 0), enabled);
    }
    static FlowDefinition flow(FlowStep... steps) {
        return new FlowDefinition("flow", "Flow", "", true, List.of(steps), null, null, null);
    }
    static FlowStep step(String id, String... variables) {
        var rules = java.util.Arrays.stream(variables).map(key -> new InjectRule(id + key, key, true,
                InjectRule.Target.REQUEST_HEADER, "X-" + key, InjectRule.ValueSourceType.VARIABLE, key, "", null)).toList();
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/" + id,
                List.of(), ""), List.of(), rules, List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
    static ExtractRule extract(String key, ExtractRule.SourceSide side) {
        return new ExtractRule("extract-" + key, key, true, side, ExtractRule.ExtractorType.HEADER, "X-Value", key, List.of());
    }
}
