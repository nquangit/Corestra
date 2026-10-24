package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRepositoryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void cachedFlowsAvoidRepeatedParsingAndWritesAndClearInvalidatesCache() {
        FakePersistence persistence = new FakePersistence();
        FlowRepository repository = repository(persistence);
        var flows = List.of(sampleFlowDefinition());
        repository.saveAll(flows);
        for (int i = 0; i < 20; i++) {
            assertEquals(flows, repository.loadAll());
            repository.saveAll(flows);
        }
        assertEquals(0, persistence.extensionDataState().getStringCallCount);
        assertEquals(1, persistence.extensionDataState().setStringCallCount());
        repository.clear();
        assertEquals(List.of(), repository.loadAll());
        repository.saveAll(flows);
        assertEquals(flows, repository.loadAll());
    }

    @Test
    void loadAllReturnsEmptyWhenStorageMissingWithoutWritingDefaults() {
        FakePersistence persistence = new FakePersistence();
        FlowRepository repository = repository(persistence);

        assertEquals(List.of(), repository.loadAll());
        assertEquals(0, persistence.extensionDataState().setStringCallCount());
        assertEquals(0, persistence.extensionDataState().deleteStringCallCount());
        assertEquals(null, persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT));
    }

    @Test
    void saveAllRoundTripsFlowDefinitionsThroughVersionedStorage() throws Exception {
        FakePersistence persistence = new FakePersistence();
        FlowSerializer serializer = new FlowSerializer();
        FlowRepository repository = new FlowRepository(
                persistence.persistence(),
                serializer,
                new PersistenceMigrationService());
        List<FlowDefinition> expectedFlows = List.of(sampleFlowDefinition());

        repository.saveAll(expectedFlows);

        String storedPayload = persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
        JsonNode storedDocument = OBJECT_MAPPER.readTree(storedPayload);

        assertNotNull(storedPayload);
        assertEquals(serializer.currentSchemaVersion(), storedDocument.get("schemaVersion").intValue());
        assertTrue(storedDocument.has("flows"));
        assertEquals(expectedFlows, repository.loadAll());
        assertEquals(expectedFlows, repository(persistence).loadAll());
    }

    @Test
    void saveAllEmptyDeletesStoredPayload() {
        FakePersistence persistence = new FakePersistence();
        persistence.extensionDataState().seedString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, "stale-payload");

        repository(persistence).saveAll(List.of());

        assertNull(persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT));
        assertEquals(0, persistence.extensionDataState().setStringCallCount());
        assertEquals(1, persistence.extensionDataState().deleteStringCallCount());
    }

    @Test
    void clearDeletesStoredPayload() {
        FakePersistence persistence = new FakePersistence();
        persistence.extensionDataState().seedString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, "stale-payload");

        repository(persistence).clear();

        assertNull(persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT));
        assertEquals(1, persistence.extensionDataState().deleteStringCallCount());
    }

    @Test
    void loadAllReturnsCurrentVersionPayloadWithoutRewrite() {
        FakePersistence persistence = new FakePersistence();
        FlowSerializer serializer = new FlowSerializer();
        List<FlowDefinition> expectedFlows = List.of(sampleFlowDefinition());
        String currentPayload = serializer.serialize(expectedFlows);
        persistence.extensionDataState().seedString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, currentPayload);

        List<FlowDefinition> restoredFlows = repository(persistence).loadAll();

        assertEquals(expectedFlows, restoredFlows);
        assertEquals(currentPayload, persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT));
        assertEquals(0, persistence.extensionDataState().setStringCallCount());
        assertEquals(0, persistence.extensionDataState().deleteStringCallCount());
    }

    @Test
    void loadAllMigratesLegacyArrayPayloadToCurrentVersion() throws Exception {
        FakePersistence persistence = new FakePersistence();
        List<FlowDefinition> expectedFlows = List.of(sampleFlowDefinition());
        String legacyPayload = OBJECT_MAPPER.writeValueAsString(expectedFlows);
        persistence.extensionDataState().seedString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, legacyPayload);

        FlowRepository repository = repository(persistence);
        List<FlowDefinition> restoredFlows = repository.loadAll();
        String migratedPayload = persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
        JsonNode migratedDocument = OBJECT_MAPPER.readTree(migratedPayload);

        assertEquals(expectedFlows, restoredFlows);
        assertFalse(legacyPayload.equals(migratedPayload));
        assertEquals(1, migratedDocument.get("schemaVersion").intValue());
        assertTrue(migratedDocument.has("flows"));
        assertEquals(1, persistence.extensionDataState().setStringCallCount());
        assertNull(persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT_QUARANTINE));
    }

    @Test
    void loadAllMigratesLegacyObjectPayloadWithoutSchemaVersionToCurrentVersion() throws Exception {
        FakePersistence persistence = new FakePersistence();
        List<FlowDefinition> expectedFlows = List.of(sampleFlowDefinition());
        ObjectNode legacyDocument = OBJECT_MAPPER.createObjectNode();
        legacyDocument.set("flows", OBJECT_MAPPER.valueToTree(expectedFlows));
        legacyDocument.put("legacyMarker", "retained");
        String legacyPayload = OBJECT_MAPPER.writeValueAsString(legacyDocument);
        persistence.extensionDataState().seedString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, legacyPayload);

        List<FlowDefinition> restoredFlows = repository(persistence).loadAll();
        String migratedPayload = persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
        JsonNode migratedDocument = OBJECT_MAPPER.readTree(migratedPayload);

        assertEquals(expectedFlows, restoredFlows);
        assertFalse(legacyPayload.equals(migratedPayload));
        assertEquals(1, migratedDocument.get("schemaVersion").intValue());
        assertEquals("retained", migratedDocument.get("legacyMarker").textValue());
        assertEquals(1, persistence.extensionDataState().setStringCallCount());
    }

    @Test
    void loadAllQuarantinesCorruptPayloadAndReturnsEmpty() {
        assertInvalidPayloadRecovered("{not-json");
    }

    @Test
    void loadAllQuarantinesUnsupportedSchemaVersionPayloadAndReturnsEmpty() {
        assertInvalidPayloadRecovered("{\"schemaVersion\":99,\"flows\":[]}");
    }

    private static FlowRepository repository(FakePersistence persistence) {
        return new FlowRepository(
                persistence.persistence(),
                new FlowSerializer(),
                new PersistenceMigrationService());
    }

    private static void assertInvalidPayloadRecovered(String invalidPayload) {
        FakePersistence persistence = new FakePersistence();
        persistence.extensionDataState().seedString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, invalidPayload);

        List<FlowDefinition> restoredFlows = repository(persistence).loadAll();

        assertEquals(List.of(), restoredFlows);
        assertNull(persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT));
        assertEquals(
                invalidPayload,
                persistence.extensionDataState().getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT_QUARANTINE));
        assertEquals(1, persistence.extensionDataState().setStringCallCount());
        assertEquals(1, persistence.extensionDataState().deleteStringCallCount());
    }

    private static FlowDefinition sampleFlowDefinition() {
        FlowStep authStep = new FlowStep(
                "step-auth",
                "Authentication step",
                true,
                "Collects and reuses a CSRF token.",
                new StoredRequestTemplate(
                        "POST",
                        "https",
                        "example.test",
                        443,
                        "/login",
                        List.of("Host: example.test", "Content-Type: application/x-www-form-urlencoded"),
                        "username=alice&password=secret"),
                List.of(new ExtractRule(
                        "extract-csrf",
                        "Capture CSRF",
                        true,
                        ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.REGEX,
                        "name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"",
                        "csrfToken",
                        List.of("trim"))),
                List.of(new InjectRule(
                        "inject-csrf",
                        "Inject CSRF",
                        true,
                        InjectRule.Target.REQUEST_FORM_FIELD,
                        "csrf",
                        InjectRule.ValueSourceType.VARIABLE,
                        "csrfToken",
                        null,
                        InjectRule.WriteStrategy.OVERWRITE)),
                List.of(new FlowStep.StepPrecondition(
                        FlowStep.PreconditionType.VARIABLE_PRESENT,
                        "bootstrapToken",
                        "Requires a bootstrap token.")),
                FlowStep.StepErrorStrategy.FAIL_FLOW);

        FlowStep refreshStep = new FlowStep(
                "step-refresh",
                "Refresh step",
                false,
                "Refreshes the session after authentication.",
                new StoredRequestTemplate(
                        "GET",
                        "https",
                        "example.test",
                        443,
                        "/refresh",
                        List.of("Host: example.test"),
                        ""),
                List.of(),
                List.of(new InjectRule(
                        "inject-session",
                        "Inject session cookie",
                        true,
                        InjectRule.Target.REQUEST_COOKIE,
                        "sid",
                        InjectRule.ValueSourceType.VARIABLE,
                        "sessionCookie",
                        null,
                        InjectRule.WriteStrategy.OVERWRITE)),
                List.of(new FlowStep.StepPrecondition(
                        FlowStep.PreconditionType.STEP_COMPLETED,
                        "step-auth",
                        "Runs after authentication completes.")),
                FlowStep.StepErrorStrategy.CONTINUE_FLOW);

        return new FlowDefinition(
                "flow-login",
                "Login flow",
                "Authenticates and refreshes a session.",
                true,
                List.of(authStep, refreshStep),
                new FlowRuleSet(
                        List.of(new ExtractRule(
                                "extract-session-cookie",
                                "Capture session cookie",
                                true,
                                ExtractRule.SourceSide.RESPONSE,
                                ExtractRule.ExtractorType.COOKIE,
                                "sid",
                                "sessionCookie",
                                List.of())),
                        List.of(new InjectRule(
                                "inject-trace-header",
                                "Inject trace header",
                                true,
                                InjectRule.Target.REQUEST_HEADER,
                                "X-Corestra-Trace",
                                InjectRule.ValueSourceType.STATIC,
                                null,
                                "enabled",
                                InjectRule.WriteStrategy.OVERWRITE))),
                new AggregationPlan(
                        true,
                        AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS,
                        "step-refresh",
                        List.of("csrfToken", "sessionCookie")),
                new FlowTriggerConfig(
                        List.of("step-auth"),
                        List.of("bootstrap-flow"),
                        "recover-session-flow",
                        true,
                        List.of(new FlowTriggerConfig.FollowUpFlowHook(
                                FlowTriggerConfig.FollowUpCondition.ON_FAILURE,
                                "recover-session-flow"))));
    }

    private static final class FakePersistence {
        private final PersistedObjectState extensionDataState = new PersistedObjectState();
        private final PreferencesState preferencesState = new PreferencesState();
        private final Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method.getName(), args);
                    }

                    return switch (method.getName()) {
                        case "extensionData" -> extensionDataState.proxy();
                        case "preferences" -> preferencesState.proxy();
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });

        private Persistence persistence() {
            return persistence;
        }

        private PersistedObjectState extensionDataState() {
            return extensionDataState;
        }
    }

    private static final class PersistedObjectState {
        private final Map<String, String> stringValues = new HashMap<>();
        private int setStringCallCount;
        private int getStringCallCount;
        private int deleteStringCallCount;
        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(instance, method.getName(), args);
                    }

                    return switch (method.getName()) {
                        case "getString" -> { getStringCallCount++; yield stringValues.get((String) args[0]); }
                        case "setString" -> {
                            setStringCallCount++;
                            stringValues.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "deleteString" -> {
                            deleteStringCallCount++;
                            stringValues.remove((String) args[0]);
                            yield null;
                        }
                        case "stringKeys" -> Set.copyOf(stringValues.keySet());
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });

        private PersistedObject proxy() {
            return proxy;
        }

        private void seedString(String key, String value) {
            stringValues.put(key, value);
        }

        private String getString(String key) {
            return stringValues.get(key);
        }

        private int setStringCallCount() {
            return setStringCallCount;
        }

        private int deleteStringCallCount() {
            return deleteStringCallCount;
        }
    }

    private static final class PreferencesState {
        private final Preferences proxy = (Preferences) Proxy.newProxyInstance(
                Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(instance, method.getName(), args);
                    }

                    throw new UnsupportedOperationException(method.toString());
                });

        private Preferences proxy() {
            return proxy;
        }
    }

    private static Object handleObjectMethod(Object proxy, String methodName, Object[] args) {
        return switch (methodName) {
            case "toString" -> proxy.getClass().getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(methodName);
        };
    }
}
