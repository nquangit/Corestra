package dev.nquangit.corestra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionScopeTest {
    @Test
    void noAlternativesUsesSharedConditionsRegardlessOfOldRequirementFlag() throws Exception {
        var mapper = new ObjectMapper();
        for (List<SessionScopeCriterion> common : List.of(List.<SessionScopeCriterion>of(),
                List.of(criterion(SessionCriterionType.METHOD, "", "^GET$")))) {
            var scope = new SessionScope(SessionScopeMode.CUSTOM, common, List.of());
            var oldJson = mapper.valueToTree(scope);
            ((com.fasterxml.jackson.databind.node.ObjectNode) oldJson).put("requireMatchingRule", true);
            SessionScope restored = mapper.treeToValue(oldJson, SessionScope.class);
            assertEquals(scope, restored);
            assertTrue(restored.rules().isEmpty());
            assertTrue(restored.matches(traffic("a", 443, "https", "/", "")));
            assertTrue(restored.matches(traffic("another-host", 8443, "http", "/another-path", "")));
            assertEquals(common.isEmpty(), restored.matches(new SessionTrafficContext(
                    "PROXY", false, "POST", "a", "/", List.of(), "")));
            assertFalse(mapper.writeValueAsString(restored).contains("requireMatchingRule"));
        }
    }

    @Test
    void generatedEmptyScopePlaceholdersAreNormalizedBeforeRuntimeMatching() throws Exception {
        var mapper = new ObjectMapper();
        for (String criteria : List.of("[]", "[{\"type\":\"HOST\",\"selector\":\"\",\"pattern\":\"(?!)\"}]")) {
            var scope = mapper.readValue("""
                    {"mode":"CUSTOM","criteria":[{"type":"HEADER","selector":"X-User","pattern":"^Alice$"}],
                    "rules":[{"name":"Empty custom scope","criteria":
                    """ + criteria + "}]}", SessionScope.class);
            assertTrue(scope.rules().isEmpty());
            assertTrue(scope.matches(traffic("a", 443, "https", "/", "Alice")));
            assertFalse(scope.matches(traffic("a", 443, "https", "/", "Bob")));
            assertEquals(scope, mapper.readValue(mapper.writeValueAsString(scope), SessionScope.class));
        }
    }

    @Test
    void missingRequirementRetainsLegacyMatchingAndCannotBypassExistingRules() throws Exception {
        var mapper = new ObjectMapper();
        var empty = mapper.readValue("{\"mode\":\"CUSTOM\",\"criteria\":[],\"rules\":[]}", SessionScope.class);
        assertTrue(empty.matches(traffic("a", 443, "https", "/", "")));
        var commonOnly = mapper.readValue("""
                {"mode":"CUSTOM","criteria":[{"type":"HOST","selector":"","pattern":"^a$"}]}
                """, SessionScope.class);
        assertTrue(commonOnly.matches(traffic("a", 443, "https", "/", "")));
        assertFalse(commonOnly.matches(traffic("b", 443, "https", "/", "")));
        for (String extra : List.of("", ",\"requireMatchingRule\":false")) {
            var alternative = mapper.readValue("""
                    {"mode":"CUSTOM","criteria":[],"rules":[
                    {"name":"A","criteria":[{"type":"HOST","selector":"","pattern":"^a$"}]}]
                    """ + extra + "}", SessionScope.class);
            assertTrue(alternative.matches(traffic("a", 443, "https", "/", "")));
            assertFalse(alternative.matches(traffic("b", 443, "https", "/", "")));
        }
    }

    @Test
    void hostAlternativesKeepTheirOwnPortPathProtocolAndUserHeader() {
        var scope = new SessionScope(SessionScopeMode.CUSTOM, List.of(), List.of(
                rule("Alice", "^api\\.example$", "^443$", "^/api(?:/|$)", "^Alice$"),
                rule("Bob", "^admin\\.example$", "^8443$", "^/admin(?:/|$)", "^Bob$")));

        assertTrue(scope.matches(traffic("api.example", 443, "https", "/api/orders", "Alice")));
        assertTrue(scope.matches(traffic("admin.example", 8443, "https", "/admin/users", "Bob")));
        assertFalse(scope.matches(traffic("api.example", 443, "https", "/api/orders", "Bob")));
        assertFalse(scope.matches(traffic("admin.example", 8443, "https", "/admin/users", "Alice")));
        assertFalse(scope.matches(traffic("api.example", 8443, "https", "/api/orders", "Alice")));
        assertFalse(scope.matches(traffic("api.example", 443, "http", "/api/orders", "Alice")));
        assertFalse(scope.matches(traffic("api.example", 443, "https", "/admin/users", "Alice")));
        assertFalse(scope.matches(traffic("api.example.attacker.test", 443, "https", "/api/orders", "Alice")));
    }

    @Test
    void commonConditionsApplyToEveryAlternative() {
        var scope = new SessionScope(SessionScopeMode.CUSTOM,
                List.of(criterion(SessionCriterionType.METHOD, "", "^POST$")),
                List.of(new SessionScopeRule("A", List.of(criterion(SessionCriterionType.HOST, "", "^a$"))),
                        new SessionScopeRule("B", List.of(criterion(SessionCriterionType.HOST, "", "^b$")))));
        for (String host : List.of("a", "b")) {
            assertTrue(scope.matches(new SessionTrafficContext("PROXY", false, "POST", host, "/", List.of(), "")));
            assertFalse(scope.matches(new SessionTrafficContext("PROXY", true, "GET", host, "/", List.of(), "")));
        }
    }

    @Test
    void headerCriterionRequiresPresenceEvenWhenRegexMatchesEmptyText() {
        for (String pattern : List.of("", ".*", "^$")) {
            var criterion = criterion(SessionCriterionType.HEADER, "X-User", pattern);
            assertFalse(criterion.matches("GET", "api", "/", List.of("X-Other: Alice"), ""));
            assertTrue(criterion.matches("GET", "api", "/", List.of("x-user:"), ""));
        }
        assertTrue(criterion(SessionCriterionType.HEADER, "X-User", "^Alice$")
                .matches("GET", "api", "/", List.of("x-USER: Alice"), ""));
        assertFalse(criterion(SessionCriterionType.HEADER, "", ".*").matches("GET", "api", "/", List.of(), ""));
    }

    @Test
    void unavailableTransportMetadataAndEmptyOrInvalidRulesFailClosed() {
        var legacyContext = new SessionTrafficContext("PROXY", true, "GET", "api", "/", List.of(), "");
        for (SessionCriterionType type : List.of(SessionCriterionType.PORT, SessionCriterionType.PROTOCOL)) {
            assertFalse(criterion(type, "", "").matches(legacyContext));
        }
        assertFalse(new SessionScopeRule("Empty", List.of()).matches(legacyContext));
        var invalid = criterion(SessionCriterionType.HOST, "", "[");
        assertTrue(invalid.validationError().isPresent());
        assertFalse(invalid.matches(legacyContext));
        assertThrows(IllegalArgumentException.class, () -> new SessionScopeRule("Common", List.of()));
    }

    @Test
    void legacyAndBurpScopeSemanticsArePreserved() throws Exception {
        var mapper = new ObjectMapper();
        SessionScope legacy = mapper.readValue("""
                {"mode":"CUSTOM","criteria":[
                {"type":"HOST","selector":"","pattern":"^a$"},
                {"type":"HOST","selector":"","pattern":"^b$"}]}
                """, SessionScope.class);
        assertFalse(legacy.matches(traffic("a", 443, "https", "/", "")));
        assertFalse(legacy.matches(traffic("b", 443, "https", "/", "")));
        assertTrue(SessionScope.custom(List.of()).matches(traffic("a", 443, "https", "/", "")));
        assertFalse(SessionScope.burpTargetScope().matches(traffic("a", 443, "https", "/", "")));
        assertTrue(SessionScope.burpTargetScope().matches(new SessionTrafficContext("PROXY", true, "GET", "a", "/", List.of(), "")));
    }

    @Test
    void namedRulesRoundTripWithoutSerializingCompiledPatternsOrErrors() throws Exception {
        var scope = new SessionScope(SessionScopeMode.CUSTOM,
                List.of(criterion(SessionCriterionType.METHOD, "", "^GET$")),
                List.of(rule("Alice", "^api\\.example$", "^443$", "^/api", "^Alice$")));
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(scope);
        assertFalse(json.contains("compiledPattern"));
        assertFalse(json.contains("validationError"));
        assertEquals(scope, mapper.readValue(json, SessionScope.class));
    }

    private static SessionScopeRule rule(String name, String host, String port, String path, String user) {
        return new SessionScopeRule(name, List.of(criterion(SessionCriterionType.HOST, "", host),
                criterion(SessionCriterionType.PORT, "", port), criterion(SessionCriterionType.PROTOCOL, "", "^https$"),
                criterion(SessionCriterionType.PATH, "", path), criterion(SessionCriterionType.HEADER, "X-User", user)));
    }

    private static SessionScopeCriterion criterion(SessionCriterionType type, String selector, String pattern) {
        return new SessionScopeCriterion(type, selector, pattern);
    }

    private static SessionTrafficContext traffic(String host, int port, String scheme, String path, String user) {
        return new SessionTrafficContext("PROXY", false, "GET", host, path, List.of("X-User: " + user), "", scheme, port);
    }
}
