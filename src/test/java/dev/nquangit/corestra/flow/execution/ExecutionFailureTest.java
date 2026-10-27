package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.control.*;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpTransportException;
import dev.nquangit.corestra.integration.ManualTestMessages;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.net.*;
import javax.net.ssl.SSLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class ExecutionFailureTest {
    private MontoyaObjectFactory previous;
    @BeforeEach void setup() { previous = ObjectFactoryLocator.FACTORY; ObjectFactoryLocator.FACTORY = ManualTestMessages.factory(); }
    @AfterEach void restore() { ObjectFactoryLocator.FACTORY = previous; Thread.interrupted(); }

    @Test void structuredCausesAndNoResponseAreAvailableToConditions() {
        var failures = new LinkedHashMap<Throwable, ExecutionFailureType>();
        failures.put(new SocketTimeoutException("localized text"), ExecutionFailureType.TIMEOUT);
        failures.put(new ConnectException("localized text"), ExecutionFailureType.CONNECTION_REFUSED);
        failures.put(new SocketException("localized reset text"), ExecutionFailureType.CONNECTION_ERROR);
        failures.put(new UnknownHostException("host"), ExecutionFailureType.DNS_ERROR);
        failures.put(new SSLException("tls"), ExecutionFailureType.TLS_ERROR);
        failures.put(new IllegalStateException("opaque send failure"), ExecutionFailureType.TRANSPORT_ERROR);
        var step = step("first");
        for (var entry : failures.entrySet()) {
            var result = new StepExecutor(new ExtractionService(), new InjectionService()).execute(step, context(), request -> {
                throw new IllegalStateException("wrapper", entry.getKey());
            });
            assertFalse(result.success()); assertEquals(0, result.responseStatusCode());
            assertEquals(entry.getValue(), result.failureType());
            assertTrue(matches(StepConditionSource.EXECUTION_FAILURE, StepConditionOperator.EQUALS, "true", result));
            assertTrue(matches(StepConditionSource.FAILURE_TYPE, StepConditionOperator.IN, entry.getValue().name() + ",NO_RESPONSE", result));
            assertTrue(matches(StepConditionSource.ERROR_MESSAGE, StepConditionOperator.CONTAINS, entry.getValue().name(), result));
        }
        var noResponse = new StepExecutor(new ExtractionService(), new InjectionService()).execute(step, context(), request -> null);
        assertEquals(ExecutionFailureType.NO_RESPONSE, noResponse.failureType());
        assertTrue(matches(StepConditionSource.RESPONSE_STATUS, StepConditionOperator.NOT_EXISTS, "", noResponse));
        assertFalse(matches(StepConditionSource.RESPONSE_STATUS, StepConditionOperator.EQUALS, "0", noResponse));
        var forbidden = new StepExecutor(new ExtractionService(), new InjectionService()).execute(step, context(),
                request -> ManualTestMessages.response("HTTP/1.1 403 Forbidden\r\n\r\nblocked"));
        assertEquals(ExecutionFailureType.NONE, forbidden.failureType());
        assertFalse(matches(StepConditionSource.EXECUTION_FAILURE, StepConditionOperator.EQUALS, "true", forbidden));
    }

    @Test void timeoutRecoverySleepsThenRetriesCurrentStepWithBoundedAttempts() {
        var calls = new AtomicInteger(); var sleeps = new ArrayList<Long>();
        var engine = new FlowEngine(new ExtractionService(), new InjectionService(), request -> {
            if (calls.incrementAndGet() <= 2) throw new IllegalStateException(new SocketTimeoutException("timeout"));
            return ManualTestMessages.response("ok");
        }, FlowDefinitionResolver.empty(), sleeps::add);
        var result = engine.execute(flow(step("first").withConditionActionRules(List.of(recovery(2)))), context());
        assertTrue(result.success(), result.failureMessage());
        assertEquals(3, calls.get()); assertEquals(List.of(10_000L, 10_000L), sleeps);
        assertEquals(List.of(ExecutionFailureType.TIMEOUT, ExecutionFailureType.TIMEOUT, ExecutionFailureType.NONE),
                result.stepRecords().stream().map(StepExecutionRecord::failureType).toList());
    }

    @Test void missingInputsAndCancellationCannotBeRetriedByAnExecutionFailureCondition() {
        var calls = new AtomicInteger();
        var engine = new FlowEngine(new ExtractionService(), new InjectionService(), request -> { calls.incrementAndGet(); return ManualTestMessages.response("ok"); });
        var injection = new InjectRule("token", "Token", true, InjectRule.Target.REQUEST_HEADER, "Authorization",
                InjectRule.ValueSourceType.VARIABLE, "auth.token", "", null);
        var result = engine.execute(flow(step("first").withInjectRules(List.of(injection)).withConditionActionRules(List.of(recovery(3)))),
                ExecutionContext.start("run", "flow", Map.of(FlowInputValidator.REQUIRE_INPUTS, "true"), 0));
        assertFalse(result.success()); assertEquals(0, calls.get()); assertTrue(result.actionRecords().isEmpty());
        assertEquals(ExecutionFailureType.INPUT_ERROR, result.stepRecords().getFirst().failureType());
        var interruptedEngine = new FlowEngine(new ExtractionService(), new InjectionService(), request -> {
            calls.incrementAndGet(); throw new IllegalStateException(new InterruptedException("cancelled"));
        });
        try {
            var cancelled = interruptedEngine.execute(flow(step("first").withConditionActionRules(List.of(recovery(3)))), context());
            assertFalse(cancelled.success()); assertEquals(1, calls.get()); assertTrue(cancelled.actionRecords().isEmpty());
            assertEquals(ExecutionFailureType.CANCELLED, cancelled.stepRecords().getFirst().failureType());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void exhaustedNetworkRecoveryRetainsItsCategoryAndNeverSendsTheNextStep() {
        var calls = new AtomicInteger();
        var engine = new FlowEngine(new ExtractionService(), new InjectionService(), request -> {
            calls.incrementAndGet(); throw new IllegalStateException(new SocketTimeoutException("timeout"));
        }, FlowDefinitionResolver.empty(), millis -> {});
        var first = step("first").withConditionActionRules(List.of(recovery(1)));
        var flow = new FlowDefinition("flow", "Flow", "", true, List.of(first, step("next")), null, null, null);
        var result = engine.execute(flow, context());
        assertFalse(result.success()); assertEquals(2, calls.get());
        assertTrue(result.failureMessage().contains("TIMEOUT"), result.failureMessage());
        assertTrue(result.failureMessage().contains("limit reached (1)"));
    }

    @Test void logicalCancellationDoesNotInterruptTheThreadOrCancelTheNextRun() {
        assertFalse(Thread.currentThread().isInterrupted());
        var executor = new StepExecutor(new ExtractionService(), new InjectionService());
        var cancelled = executor.execute(step("first"), context(), request -> {
            throw new dev.nquangit.corestra.interaction.InteractionAbortedException("User cancelled");
        });
        assertEquals(ExecutionFailureType.CANCELLED, cancelled.failureType());
        assertFalse(Thread.currentThread().isInterrupted());
        assertTrue(executor.execute(step("first"), context(), request -> ManualTestMessages.response("ok")).success());
    }

    @Test void categoriesRoundTripAndLegacyRecordsRemainReadable() throws Exception {
        var mapper = new ObjectMapper();
        var original = new StepExecutionRecord("s", "", "", 0, false, "timeout", 0, 1, Map.of(), ExecutionFailureType.TIMEOUT);
        String json = mapper.writeValueAsString(original);
        assertEquals(original, mapper.readValue(json, StepExecutionRecord.class));
        ObjectNode legacy = (ObjectNode) mapper.readTree(json); legacy.remove("failureType");
        assertEquals(ExecutionFailureType.UNKNOWN, mapper.treeToValue(legacy, StepExecutionRecord.class).failureType());
        var serializer = new dev.nquangit.corestra.persistence.FlowSerializer();
        for (var source : List.of(StepConditionSource.EXECUTION_FAILURE, StepConditionSource.FAILURE_TYPE, StepConditionSource.ERROR_MESSAGE)) {
            var condition = new StepCondition("c", "C", true, source, "", StepConditionOperator.EQUALS,
                    ConditionValueSource.STATIC, source == StepConditionSource.EXECUTION_FAILURE ? "true" : "TIMEOUT", false, false);
            var rule = new StepConditionActionRule("r", "R", true, ConditionMatchPolicy.ALL, List.of(condition), recovery(2).actions());
            var flow = flow(step("first").withConditionActionRules(List.of(rule)));
            assertEquals(rule, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst().steps().getFirst().conditionActionRules().getFirst());
        }
    }

    private static boolean matches(StepConditionSource source, StepConditionOperator operator, String expected, StepExecutionRecord record) {
        return new StepConditionEvaluator().matches(new StepCondition("c", "C", true, source, "", operator,
                ConditionValueSource.STATIC, expected, false, false), record, context(), new ArrayList<>());
    }
    private static StepConditionActionRule recovery(int retries) {
        return new StepConditionActionRule("recover", "Recover", true, ConditionMatchPolicy.ALL,
                List.of(new StepCondition("failed", "Failed", true, StepConditionSource.EXECUTION_FAILURE, "",
                        StepConditionOperator.EQUALS, ConditionValueSource.STATIC, "true", false, false)),
                List.of(new StepAction("sleep", "Sleep", true, StepActionType.SLEEP, Map.of("seconds", "10")),
                        new StepAction("retry", "Retry", true, StepActionType.RETRY_CURRENT_STEP, Map.of("maxAttempts", Integer.toString(retries)))));
    }
    private static ExecutionContext context() { return ExecutionContext.start("run", "flow", Map.of(), 0); }
    private static FlowDefinition flow(FlowStep step) { return new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null); }
    private static FlowStep step(String id) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/" + id, List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
}
