package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.ui.access.AccessUiController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InjectionVariableCatalogTest {
    @Test
    void combinesPreviousExtractorsCredentialsAuthStateAndDataWithoutExposingSecrets() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProfile(new AuthProfile(
                "auth-1", "Alice", "user", "manual", Map.of("username", "alice"),
                new AuthState(Map.of("accessToken", "secret-token"), Map.of(), Map.of(), 0L, 0L), true));
        repository.saveAuthProfile(new AuthProfile(
                "auth-2", "Bob", "user", "manual", Map.of("username", "bob"),
                AuthState.empty(), true));
        repository.saveDataProfile(DataProfile.enabled(
                "data-1", "Alice data", "auth-1", Map.of("accountId", "ACC-123")));
        FlowStep login = step("login", List.of(new ExtractRule(
                "extract", "CSRF", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER, "X-CSRF", "csrf", List.of())));
        FlowStep target = step("target", List.of());
        FlowDefinition flow = new FlowDefinition(
                "flow", "Flow", "", true, List.of(login, target), FlowRuleSet.empty(),
                AggregationPlan.disabled(), FlowTriggerConfig.none());

        List<InjectionVariableCatalog.VariableOption> options = new InjectionVariableCatalog(
                new AccessUiController(repository)).options(flow, target);

        assertEquals(List.of("csrf", "credentials.username", "auth.accessToken", "data.accountId"),
                options.stream().map(InjectionVariableCatalog.VariableOption::key).toList());
        InjectionVariableCatalog.VariableOption credential = options.get(1);
        assertTrue(credential.sensitive());
        assertTrue(credential.source().contains("Alice"));
        assertTrue(credential.source().contains("Bob"));
        assertFalse(credential.displayPreview().contains("alice"));
        assertEquals("Stored value: ACC-123", options.get(3).displayPreview());
    }

    private static FlowStep step(String id, List<ExtractRule> extracts) {
        return new FlowStep(
                id, id, true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), ""),
                extracts, List.of(), List.of(), null);
    }
}
