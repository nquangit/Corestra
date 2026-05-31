package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.StepTriggerRule.MatchCriterion;
import dev.nquangit.corestra.flow.StepTriggerRule.MatchCriterion.MatchType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepTriggerRuleTest {

    // ── factory helpers ────────────────────────────────────────────────────────

    @Test
    void disabledFactoryCreatesDisabledRuleWithNoCriteria() {
        StepTriggerRule rule = StepTriggerRule.disabled();
        assertFalse(rule.enabled());
        assertTrue(rule.criteria().isEmpty());
        assertTrue(rule.allowedToolTypes().isEmpty());
    }

    @Test
    void fromTemplatePrePopulatesHostAndPathCriteria() {
        StoredRequestTemplate template = new StoredRequestTemplate(
                "GET", "https", "api.example.com", 443, "/v1/login?t=1", List.of(), "");
        StepTriggerRule rule = StepTriggerRule.fromTemplate(template);

        assertFalse(rule.enabled(), "should start disabled");
        assertEquals(2, rule.criteria().size());
        assertEquals(MatchType.HOST, rule.criteria().get(0).type());
        assertEquals(MatchType.PATH, rule.criteria().get(1).type());
        // Path criterion must NOT include query string
        assertFalse(rule.criteria().get(1).pattern().contains("?"));
    }

    // ── MatchCriterion.matches ─────────────────────────────────────────────────

    @Test
    void hostCriterionMatchesOnHost() {
        MatchCriterion c = new MatchCriterion(MatchType.HOST, "", "example\\.com");
        assertTrue(c.matches("example.com", "/", List.of(), ""));
        assertFalse(c.matches("other.com", "/", List.of(), ""));
    }

    @Test
    void pathCriterionMatchesOnPath() {
        MatchCriterion c = new MatchCriterion(MatchType.PATH, "", "/api/.*");
        assertTrue(c.matches("h", "/api/v1/login", List.of(), ""));
        assertFalse(c.matches("h", "/other", List.of(), ""));
    }

    @Test
    void headerCriterionMatchesNamedHeaderValue() {
        MatchCriterion c = new MatchCriterion(MatchType.HEADER, "Authorization", "Bearer .*");
        assertTrue(c.matches("h", "/", List.of("Authorization: Bearer abc123"), ""));
        assertFalse(c.matches("h", "/", List.of("Authorization: Basic abc"), ""));
        assertFalse(c.matches("h", "/", List.of(), ""));
    }

    @Test
    void bodyCriterionMatchesBodyText() {
        MatchCriterion c = new MatchCriterion(MatchType.BODY, "", "csrf_token=\\w+");
        assertTrue(c.matches("h", "/", List.of(), "csrf_token=abc123"));
        assertFalse(c.matches("h", "/", List.of(), "no_token=here"));
    }

    @Test
    void emptyCriterionPatternMatchesAnything() {
        MatchCriterion c = new MatchCriterion(MatchType.HOST, "", "");
        assertTrue(c.matches("anything.com", "/", List.of(), ""));
    }

    @Test
    void invalidPatternNeverMatches() {
        MatchCriterion c = new MatchCriterion(MatchType.HOST, "", "[invalid");
        assertFalse(c.matches("example.com", "/", List.of(), ""));
    }

    // ── StepTriggerRule.matches ────────────────────────────────────────────────

    @Test
    void emptyToolListAllowsAllTools() {
        StepTriggerRule rule = new StepTriggerRule(true, List.of(), List.of());
        assertTrue(rule.matches("PROXY", "h", "/", List.of(), ""));
        assertTrue(rule.matches("SCANNER", "h", "/", List.of(), ""));
    }

    @Test
    void toolListFiltersTools() {
        StepTriggerRule rule = new StepTriggerRule(true, List.of("PROXY"), List.of());
        assertTrue(rule.matches("PROXY", "h", "/", List.of(), ""));
        assertFalse(rule.matches("SCANNER", "h", "/", List.of(), ""));
    }

    @Test
    void allCriteriaMustMatch() {
        StepTriggerRule rule = new StepTriggerRule(true, List.of(), List.of(
                new MatchCriterion(MatchType.HOST, "", "example\\.com"),
                new MatchCriterion(MatchType.PATH, "", "/api/.*")
        ));
        assertTrue(rule.matches("PROXY", "example.com", "/api/login", List.of(), ""));
        assertFalse(rule.matches("PROXY", "example.com", "/other", List.of(), ""));
        assertFalse(rule.matches("PROXY", "other.com", "/api/login", List.of(), ""));
    }

    // ── Jackson round-trip ─────────────────────────────────────────────────────

    @Test
    void jacksonRoundTripPreservesAllFields() throws Exception {
        StepTriggerRule original = new StepTriggerRule(
                true,
                List.of("PROXY", "REPEATER"),
                List.of(new MatchCriterion(MatchType.HOST, "", "example\\.com"),
                        new MatchCriterion(MatchType.HEADER, "Authorization", "Bearer .*")));
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        StepTriggerRule deserialized = mapper.readValue(json, StepTriggerRule.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jacksonDeserializesLegacyJsonWithoutCriteriaField() throws Exception {
        String legacyJson = "{\"enabled\":false,\"allowedToolTypes\":[],\"hostPattern\":\"old\",\"pathPattern\":\"old\"}";
        ObjectMapper mapper = new ObjectMapper();
        StepTriggerRule rule = mapper.readValue(legacyJson, StepTriggerRule.class);
        // criteria absent in JSON → empty list; old flat fields are ignored
        assertFalse(rule.enabled());
        assertTrue(rule.criteria().isEmpty());
    }

    // ── ExecutionScope ─────────────────────────────────────────────────────────

    @Test
    void disabledFactoryDefaultsToMatchedStepScope() {
        assertEquals(StepTriggerRule.ExecutionScope.MATCHED_STEP, StepTriggerRule.disabled().scope());
        assertTrue(StepTriggerRule.disabled().stopAtStepId().isEmpty());
    }

    @Test
    void fromTemplateDefaultsToMatchedStepScope() {
        StoredRequestTemplate template = new StoredRequestTemplate(
                "GET", "https", "api.example.com", 443, "/v1/login", List.of(), "");
        StepTriggerRule rule = StepTriggerRule.fromTemplate(template);
        assertEquals(StepTriggerRule.ExecutionScope.MATCHED_STEP, rule.scope());
        assertTrue(rule.stopAtStepId().isEmpty());
    }

    @Test
    void fiveArgConstructorPreservesScope() {
        StepTriggerRule rule = new StepTriggerRule(true, List.of(), List.of(),
                StepTriggerRule.ExecutionScope.FULL_FLOW, "");
        assertEquals(StepTriggerRule.ExecutionScope.FULL_FLOW, rule.scope());
    }

    @Test
    void fiveArgConstructorPreservesStopAtStepId() {
        StepTriggerRule rule = new StepTriggerRule(true, List.of(), List.of(),
                StepTriggerRule.ExecutionScope.STOP_AT_STEP, "step-123");
        assertEquals("step-123", rule.stopAtStepId());
    }

    @Test
    void threeArgCompatConstructorDefaultsScopeToMatchedStep() {
        StepTriggerRule rule = new StepTriggerRule(true, List.of(), List.of());
        assertEquals(StepTriggerRule.ExecutionScope.MATCHED_STEP, rule.scope());
        assertTrue(rule.stopAtStepId().isEmpty());
    }

    @Test
    void jacksonRoundTripPreservesScope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StepTriggerRule original = new StepTriggerRule(true, List.of("PROXY"), List.of(),
                StepTriggerRule.ExecutionScope.STOP_AT_STEP, "step-abc");
        String json = mapper.writeValueAsString(original);
        StepTriggerRule deserialized = mapper.readValue(json, StepTriggerRule.class);
        assertEquals(original.scope(), deserialized.scope());
        assertEquals(original.stopAtStepId(), deserialized.stopAtStepId());
    }

    @Test
    void jacksonDeserializesLegacyJsonWithoutScopeDefaultsToMatchedStep() throws Exception {
        // Old JSON has no scope/stopAtStepId — must default gracefully.
        String legacyJson = "{\"enabled\":false,\"allowedToolTypes\":[],\"criteria\":[]}";
        ObjectMapper mapper = new ObjectMapper();
        StepTriggerRule rule = mapper.readValue(legacyJson, StepTriggerRule.class);
        assertEquals(StepTriggerRule.ExecutionScope.MATCHED_STEP, rule.scope());
        assertTrue(rule.stopAtStepId().isEmpty());
    }
}
