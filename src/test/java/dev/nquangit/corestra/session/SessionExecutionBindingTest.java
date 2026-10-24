package dev.nquangit.corestra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.persistence.FlowSerializer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionExecutionBindingTest {
    @Test void originNormalizationAcceptsExactHttpOriginsAndRejectsAmbiguousDestinations() {
        assertEquals("https://api.test:443", SessionExecutionBinding.normalizeOrigin(" HTTPS://API.TEST/ "));
        assertEquals("http://api.test:8080", SessionExecutionBinding.normalizeOrigin("http://api.test:8080"));
        assertEquals("https://[::1]:443", SessionExecutionBinding.normalizeOrigin("https://[::1]"));
        for (String invalid : List.of("api.test", "*.test", "https://*.test", "ftp://api.test", "https://user:pass@api.test",
                "https://api.test/path", "https://api.test?", "https://api.test#", "https://api.test:0", "https://api.test:65536", "https://api.test:bad", "https://api.test:")) {
            assertThrows(IllegalArgumentException.class, () -> SessionExecutionBinding.normalizeOrigin(invalid), invalid);
        }
    }

    @Test void originBoundaryIncludesSchemeAndPortAndMetadataIsImmutableAndValidated() {
        var binding = new SessionExecutionBinding("x", 2, "hash", "REPEATER", List.of("https://api.test", "https://api.test:443/"));
        assertEquals(List.of("https://api.test:443"), binding.origins());
        var context = ExecutionContext.start("run", "flow", binding.metadata(), 1);
        assertEquals(binding, SessionExecutionBinding.read(context));
        assertTrue(SessionExecutionBinding.isBound(context));
        binding.requireDestination(traffic("https", 443));
        assertThrows(SessionBindingException.class, () -> binding.requireDestination(traffic("http", 443)));
        assertThrows(SessionBindingException.class, () -> binding.requireDestination(traffic("https", 8443)));
        Map<String, String> broken = new HashMap<>(binding.metadata()); broken.remove("corestra.boundSession.generation");
        assertThrows(SessionBindingException.class, () -> SessionExecutionBinding.read(ExecutionContext.start("run", "flow", broken, 1)));
        assertThrows(UnsupportedOperationException.class, () -> binding.origins().add("https://other.test:443"));
    }

    @Test void existingProjectsDefaultToUnboundAndFlowPersistenceRoundTripsTheNewFields() throws Exception {
        var mapper = new ObjectMapper();
        var old = mapper.readValue("{\"enabled\":true,\"criteria\":[],\"scope\":\"FULL_FLOW\"}", StepTriggerRule.class);
        assertFalse(old.inheritSession()); assertTrue(old.sessionOrigins().isEmpty());
        var trigger = new StepTriggerRule(true, List.of("REPEATER"), List.of(), StepTriggerRule.ExecutionScope.FULL_FLOW,
                "", true, List.of("https://API.TEST", "https://api.test:443"));
        assertEquals(trigger, mapper.readValue(mapper.writeValueAsString(trigger), StepTriggerRule.class));
        var step = new FlowStep("step", "Step", true, "", new StoredRequestTemplate("GET", "https", "api.test", 443,
                "/", List.of(), ""), List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW).withTriggerRule(trigger);
        var flow = new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null);
        var serializer = new FlowSerializer();
        assertEquals(flow, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst());
        assertTrue(step.withName("Renamed").withEnabled(false).triggerRule().inheritSession());
    }
    private static SessionTrafficContext traffic(String scheme, int port) {
        return new SessionTrafficContext("REPEATER", true, "GET", "api.test", "/", List.of(), "", scheme, port);
    }
}
