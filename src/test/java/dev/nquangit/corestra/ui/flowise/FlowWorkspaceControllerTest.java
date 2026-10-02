package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.persistence.FlowRepository;
import dev.nquangit.corestra.persistence.FlowSerializer;
import dev.nquangit.corestra.persistence.PersistenceMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlowWorkspaceControllerTest {

    private static final StoredRequestTemplate TEMPLATE =
            new StoredRequestTemplate("GET", "https", "example.com", 443, "/", List.of(), "");

    private InMemoryPersistence persistence;
    private FlowWorkspaceController controller;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryPersistence();
        controller = new FlowWorkspaceController(flowRepository(persistence));
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void startsWithNoFlows() {
        assertTrue(controller.flows().isEmpty());
    }

    @Test
    void loadsExistingFlowsFromRepository() {
        FlowDefinition flow = emptyFlow("f1", "Flow 1");
        FlowRepository repo = flowRepository(persistence);
        repo.saveAll(List.of(flow));
        controller = new FlowWorkspaceController(repo);
        assertEquals(1, controller.flows().size());
        assertEquals("f1", controller.flows().get(0).id());
    }

    @Test
    void activeFlowIsNullInitially() {
        assertNull(controller.activeFlow());
    }

    @Test
    void selectedStepIsNullInitially() {
        assertNull(controller.selectedStep());
    }

    @Test
    void lastExecutionResultIsNullInitially() {
        assertNull(controller.lastExecutionResult());
    }

    // -------------------------------------------------------------------------
    // addFlow
    // -------------------------------------------------------------------------

    @Test
    void addFlowAppendsToList() {
        controller.addFlow(emptyFlow("f1", "Flow 1"));
        assertEquals(1, controller.flows().size());
    }

    @Test
    void addFlowPersists() {
        controller.addFlow(emptyFlow("f1", "Flow 1"));
        assertEquals(1, flowRepository(persistence).loadAll().size());
    }

    @Test
    void addFlowNotifiesListeners() {
        AtomicInteger count = new AtomicInteger();
        controller.addFlowChangeListener(count::incrementAndGet);
        controller.addFlow(emptyFlow("f1", "F"));
        assertEquals(1, count.get());
    }

    // -------------------------------------------------------------------------
    // removeFlow
    // -------------------------------------------------------------------------

    @Test
    void removeFlowDropsFromList() {
        FlowDefinition flow = emptyFlow("f1", "F");
        controller.addFlow(flow);
        controller.removeFlow(flow);
        assertTrue(controller.flows().isEmpty());
    }

    @Test
    void removeActiveFlowClearsSelection() {
        FlowDefinition flow = emptyFlow("f1", "F");
        controller.addFlow(flow);
        controller.setActiveFlow(flow);
        controller.removeFlow(flow);
        assertNull(controller.activeFlow());
        assertNull(controller.selectedStep());
    }

    // -------------------------------------------------------------------------
    // updateFlow
    // -------------------------------------------------------------------------

    @Test
    void updateFlowReplacesByIdInList() {
        controller.addFlow(emptyFlow("f1", "Original"));
        controller.updateFlow(emptyFlow("f1", "Renamed"));
        assertEquals("Renamed", controller.flows().get(0).name());
    }

    @Test
    void updateActiveFlowUpdatesActiveReference() {
        FlowDefinition flow = emptyFlow("f1", "F");
        controller.addFlow(flow);
        controller.setActiveFlow(flow);
        controller.updateFlow(emptyFlow("f1", "Renamed"));
        assertEquals("Renamed", controller.activeFlow().name());
    }

    @Test
    void updateFlowThrowsWhenIdNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateFlow(emptyFlow("unknown", "X")));
    }

    // -------------------------------------------------------------------------
    // setActiveFlow
    // -------------------------------------------------------------------------

    @Test
    void setActiveFlowSelectsFirstStep() {
        FlowStep first = step("s1", "First");
        FlowStep second = step("s2", "Second");
        FlowDefinition flow = flow("f1", "F", List.of(first, second));
        controller.addFlow(flow);
        controller.setActiveFlow(flow);
        assertSame(first, controller.selectedStep());
    }

    @Test
    void setActiveEmptyFlowClearsSelectedStep() {
        FlowDefinition populated = flow("f1", "Populated", List.of(step("s1", "S")));
        FlowDefinition empty = emptyFlow("f2", "Empty");
        controller.addFlow(populated);
        controller.addFlow(empty);
        controller.setActiveFlow(populated);

        controller.setActiveFlow(empty);

        assertNull(controller.selectedStep());
    }

    // -------------------------------------------------------------------------
    // setLastExecutionResult
    // -------------------------------------------------------------------------

    @Test
    void setLastExecutionResultStoresResult() {
        ExecutionResult result = ExecutionResult.success("f1", "F", List.of());
        controller.setLastExecutionResult(result);
        assertSame(result, controller.lastExecutionResult());
    }

    @Test
    void setLastExecutionResultNotifiesListeners() {
        AtomicInteger count = new AtomicInteger();
        controller.addFlowChangeListener(count::incrementAndGet);
        controller.setLastExecutionResult(ExecutionResult.success("f1", "F", List.of()));
        assertEquals(1, count.get());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static FlowRepository flowRepository(InMemoryPersistence p) {
        return new FlowRepository(p.asPersistence(), new FlowSerializer(), new PersistenceMigrationService());
    }

    private static FlowDefinition emptyFlow(String id, String name) {
        return flow(id, name, List.of());
    }

    private static FlowDefinition flow(String id, String name, List<FlowStep> steps) {
        return new FlowDefinition(id, name, "", true, steps,
                FlowRuleSet.empty(), AggregationPlan.disabled(), FlowTriggerConfig.none());
    }

    private static FlowStep step(String id, String name) {
        return new FlowStep(id, name, true, "", TEMPLATE,
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    // -------------------------------------------------------------------------
    // Fake persistence
    // -------------------------------------------------------------------------

    private static final class InMemoryPersistence {
        private final Map<String, String> store = new HashMap<>();

        Persistence asPersistence() {
            PersistedObject extensionData = (PersistedObject) Proxy.newProxyInstance(
                    PersistedObject.class.getClassLoader(),
                    new Class<?>[]{PersistedObject.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getString" -> store.get((String) args[0]);
                        case "setString" -> {
                            store.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "deleteString" -> {
                            store.remove((String) args[0]);
                            yield null;
                        }
                        case "stringKeys" -> Set.copyOf(store.keySet());
                        case "toString" -> "InMemoryExtensionData";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });

            Preferences preferences = (Preferences) Proxy.newProxyInstance(
                    Preferences.class.getClassLoader(),
                    new Class<?>[]{Preferences.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "InMemoryPreferences";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });

            return (Persistence) Proxy.newProxyInstance(
                    Persistence.class.getClassLoader(),
                    new Class<?>[]{Persistence.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "extensionData" -> extensionData;
                        case "preferences" -> preferences;
                        case "toString" -> "InMemoryPersistence";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
