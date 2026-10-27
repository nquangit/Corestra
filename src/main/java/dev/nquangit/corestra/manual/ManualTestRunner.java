package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs an explicit manual range while retaining only compact continuation state between sends. */
public final class ManualTestRunner {
    private final FlowEngine engine;
    private final ManualSessionHttpClient transport;
    /** Creates a runner backed by the dedicated manual engine and session boundary. */
    public ManualTestRunner(FlowEngine engine, ManualSessionHttpClient transport) { this.engine = Objects.requireNonNull(engine); this.transport = Objects.requireNonNull(transport); }

    /** Compact continuation; raw messages live only in the bounded manual execution log. */
    public record State(Map<String, String> variables, Map<String, List<StepExecutionRecord>> history,
                        Map<String, String> binding, ManualRunOptions options) {
        /** Freezes runtime state. */
        public State { variables = Map.copyOf(variables); history = history.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))); binding = Map.copyOf(binding); }
        /** Starts a fresh variable/session context. */
        public static State empty() { return new State(Map.of(), Map.of(), Map.of(), ManualRunOptions.defaults()); }
    }
    /** Result and compact context for the next manual step. */
    public record Outcome(ExecutionResult result, State state) { }

    /** Executes a selected inclusive range. Full flow preserves actions; single-step mode suppresses them. */
    public Outcome run(FlowDefinition flow, int first, int last, boolean singleStep, Map<String, String> variables,
                       State previous, ManualRunOptions options, AtomicBoolean cancelled) {
        return run(flow, first, last, singleStep, variables, previous, options, cancelled, ignored -> { });
    }

    /** Executes a range with transient per-attempt progress; HTTP and continuation semantics remain identical. */
    public Outcome run(FlowDefinition flow, int first, int last, boolean singleStep, Map<String, String> variables,
                       State previous, ManualRunOptions options, AtomicBoolean cancelled,
                       java.util.function.Consumer<StepExecutionProgress> progress) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new dev.nquangit.corestra.session.SessionBindingException("Manual run cancelled");
        if (first < 0 || last >= flow.steps().size() || first > last) throw new IllegalArgumentException("Select a valid request range");
        if (!flow.enabled()) throw new IllegalArgumentException("Manual draft is disabled");
        List<FlowStep> steps = new ArrayList<>(flow.steps());
        for (int i = 0; i < steps.size(); i++) {
            FlowStep s = steps.get(i);
            // Preserve IDs and precondition references; disable steps outside the requested range.
            s = s.withEnabled(i >= first && i <= last && (singleStep || s.enabled()));
            if (singleStep) s = s.withConditionActionRules(List.of());
            steps.set(i, s);
        }
        if (options.mode() == ManualRunOptions.Mode.FIXED || options.mode() == ManualRunOptions.Mode.ASK
                || (options.equals(previous.options()) && !previous.binding().isEmpty())) {
            var firstRequest = steps.stream().filter(FlowStep::enabled).findFirst().orElse(null);
            if (firstRequest != null) {
                Set<String> allowed = new HashSet<>(options.additionalOrigins());
                if (options.equals(previous.options()) && !previous.binding().isEmpty()) {
                    var old = ExecutionContext.start("check", flow.id(), previous.binding(), 0);
                    allowed.addAll(dev.nquangit.corestra.session.SessionExecutionBinding.read(old).origins());
                } else allowed.add(ManualRequestCodec.origin(firstRequest.requestTemplate()));
                for (var candidate : steps) if (candidate.enabled() && !allowed.contains(ManualRequestCodec.origin(candidate.requestTemplate())))
                    throw new dev.nquangit.corestra.session.SessionBindingException("Manual range contains an origin outside the allowed session origins; no request was sent");
            }
        }
        var runnable = new FlowDefinition(flow.id(), flow.name(), flow.description(), true, steps, flow.rules(), AggregationPlan.disabled(), FlowTriggerConfig.none());
        boolean same = options.equals(previous.options());
        Map<String, String> metadata = Map.of(ManualRequestTemplate.ENABLED, "true", FlowInputValidator.REQUIRE_INPUTS, "true");
        var context = new ExecutionContext(UUID.randomUUID().toString(), flow.id(), metadata, variables, "",
                same ? previous.history() : Map.of(), System.currentTimeMillis(), 0);
        try (var active = transport.begin(context.runId(), options, same ? previous.binding() : Map.of(), cancelled)) {
            if (cancelled.get()) throw new dev.nquangit.corestra.session.SessionBindingException("Manual run cancelled");
            ExecutionResult result = engine.execute(runnable, context, progress);
            var finalContext = result.finalContext().orElse(context);
            Map<String, List<StepExecutionRecord>> compact = new LinkedHashMap<>();
            finalContext.stepHistory().forEach((key, records) -> {
                if (!records.isEmpty()) {
                    var r = records.getLast();
                    compact.put(key, List.of(new StepExecutionRecord(r.stepId(), "", "", r.responseStatusCode(), r.success(),
                            r.errorMessage(), r.startedAtEpochMillis(), r.completedAtEpochMillis(), Map.of(), r.failureType())));
                }
            });
            return new Outcome(result, new State(finalContext.variables(), compact, active.binding(), options));
        }
    }
}
