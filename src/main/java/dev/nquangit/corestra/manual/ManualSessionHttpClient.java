package dev.nquangit.corestra.manual;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.*;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.session.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolates manual runs while applying project scope, selection and bound-session checks before every send. */
public final class ManualSessionHttpClient implements HttpClient {
    private final HttpClient delegate;
    private final SessionManager manager;
    private final SessionSelectionService selector;
    private final InjectionService injections;
    private final SessionFlowBindingService bindings;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /** Builds a manual boundary around the project routing transport, never around live HTTP handlers. */
    public ManualSessionHttpClient(HttpClient delegate, SessionManager manager, SessionSelectionService selector, InjectionService injections) {
        this.delegate = Objects.requireNonNull(delegate); this.manager = Objects.requireNonNull(manager);
        this.selector = Objects.requireNonNull(selector); this.injections = Objects.requireNonNull(injections);
        bindings = new SessionFlowBindingService(manager, injections, id -> Optional.empty());
    }

    /** Opens a bounded-lifetime run; callers must close it even on cancellation/failure. */
    public Run begin(String runId, ManualRunOptions options, Map<String, String> previousBinding, AtomicBoolean cancelled) {
        Run run = new Run(runId, options, previousBinding, cancelled);
        if (runs.putIfAbsent(runId, run) != null) throw new IllegalStateException("Manual run already active");
        return run;
    }

    /** A single sequential run and its optional pinned session; never persisted. */
    public final class Run implements AutoCloseable {
        private final String id;
        private final ManualRunOptions options;
        private final AtomicBoolean cancelled;
        private Map<String, String> binding;
        private SessionInjectionPlan currentPlan;
        private Run(String id, ManualRunOptions options, Map<String, String> previous, AtomicBoolean cancelled) {
            this.id = id; this.options = options; this.cancelled = cancelled;
            binding = previous == null ? Map.of() : Map.copyOf(previous);
        }
        /** Returns runtime binding metadata for an explicit continuation with unchanged options. */
        public Map<String, String> binding() { return binding; }
        /** Removes the runtime registration on completion or cancellation. */
        @Override public void close() { runs.remove(id, this); currentPlan = null; }
        private void check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new SessionBindingException("Manual run cancelled");
        }
        private ExecutionContext bound(ExecutionContext context) {
            Map<String, String> metadata = new LinkedHashMap<>(context.metadata()); metadata.putAll(binding);
            return new ExecutionContext(context.runId(), context.flowId(), metadata, context.variables(), context.aggregationBuffer(),
                    context.stepHistory(), context.startedAtEpochMillis(), context.completedAtEpochMillis());
        }
    }

    private Run run(ExecutionContext context) {
        Run run = runs.get(context.runId());
        if (run == null) throw new SessionBindingException("Manual execution context is closed or missing");
        run.check(); return run;
    }

    @Override public HttpRequest prepareForExecution(HttpRequest request, ExecutionContext context) {
        try { return prepare(request, context); }
        catch (SessionBindingException | dev.nquangit.corestra.interaction.InteractionAbortedException aborted) { throw aborted; }
        catch (RuntimeException failed) { throw new SessionBindingException("Manual session preparation failed; request was not sent"); }
    }

    private HttpRequest prepare(HttpRequest request, ExecutionContext context) {
        Run run = run(context); run.currentPlan = null;
        if (!run.binding.isEmpty()) return bindings.apply(run.bound(context), request);
        if (run.options.mode() == ManualRunOptions.Mode.NONE) return request;
        SessionTrafficContext traffic = SessionTrafficContexts.fromRequest(request, run.options.tool());
        var config = selector.configuration();
        SessionInjectionPlan plan;
        boolean pin = run.options.mode() == ManualRunOptions.Mode.FIXED || run.options.mode() == ManualRunOptions.Mode.ASK;
        String stripHeader = "";
        if (run.options.mode() == ManualRunOptions.Mode.ASK) plan = selector.chooseExisting(traffic, 300);
        else if (run.options.mode() == ManualRunOptions.Mode.SCOPED && config.matches(traffic)) {
            plan = selector.choose(traffic, config); pin = true; stripHeader = config.stripHeader();
        } else {
            SessionProfile profile = run.options.mode() == ManualRunOptions.Mode.FIXED
                    ? manager.profiles().stream().filter(p -> p.id().equals(run.options.profileId()) && p.enabled()).findFirst()
                            .orElseThrow(() -> new SessionBindingException("Selected Session Profile is disabled or missing"))
                    : manager.selectProfile(traffic).profile().orElse(null);
            if (profile == null) return request;
            SessionGuard.enter();
            try { plan = manager.prepareInjectionRules(profile, traffic); }
            finally { SessionGuard.exit(); }
            boolean configured = profile.usesLinkedAuthProfile() || profile.injectionRules().stream().anyMatch(r -> r.enabled() && r.allowsTool(traffic.toolTypeName()));
            if (configured || pin) plan = pin ? manager.prepareBoundSession(profile.id(), traffic) : manager.prepareScopedSession(profile.id(), traffic);
        }
        run.check();
        if (pin) {
            var rule = new StepTriggerRule(true, List.of(), List.of(), StepTriggerRule.ExecutionScope.FULL_FLOW, "", true, run.options.additionalOrigins());
            ExecutionContext bound = bindings.bind(context, new SessionRequestSession(plan, traffic), rule);
            run.binding = SessionExecutionBinding.read(bound).metadata();
            if (!stripHeader.isBlank()) request = request.withRemovedHeader(stripHeader);
            return bindings.apply(bound, request);
        }
        run.currentPlan = plan;
        if (plan.rules().isEmpty()) return request;
        return HttpRequestMutationAdapter.apply(request, injections.applyRequired(plan.rules(), Map.of(),
                new InjectionService.MutableHttpRequest(request.method() + " " + request.path() + " " + request.httpVersion(),
                        request.headers().stream().map(h -> h.name() + ": " + h.value()).toList(), request.bodyToString())));
    }

    @Override public HttpResponse send(HttpRequest request) { throw new SessionBindingException("Manual requests require a registered execution context"); }

    @Override public HttpResponse send(HttpRequest request, HttpSendMode mode, ExecutionContext context) {
        Run run = run(context);
        SessionInjectionPlan sentPlan = run.currentPlan;
        if (!run.binding.isEmpty()) sentPlan = bindings.validate(run.bound(context), request);
        else if (run.currentPlan != null && !run.currentPlan.rules().isEmpty()) {
            var actual = manager.prepareScopedSession(run.currentPlan.profileId(), SessionTrafficContexts.fromRequest(request, run.options.tool()));
            if (!SessionInjectionFingerprint.of(actual).equals(SessionInjectionFingerprint.of(run.currentPlan)))
                throw new SessionBindingException("Session changed while preparing the manual request");
        }
        run.check();
        SessionGuard.enter();
        try {
            HttpResponse response = delegate.send(request, mode);
            SessionInjectionPlan plan = sentPlan;
            if (plan != null) {
                try {
                    var rules = manager.profiles().stream().filter(p -> p.id().equals(plan.profileId())).flatMap(p -> p.extractionRules().stream())
                            .filter(r -> r.enabled() && r.allowsTool(run.options.tool())).map(SessionExtractionRule::toExtractRule).toList();
                    if (rules.isEmpty() || Thread.currentThread().isInterrupted()) return response;
                    if (!plan.rules().isEmpty()) {
                        var current = manager.prepareScopedSession(plan.profileId(), SessionTrafficContexts.fromRequest(request, run.options.tool()));
                        if (!SessionInjectionFingerprint.of(current).equals(SessionInjectionFingerprint.of(plan))) return response;
                    }
                    if (Thread.currentThread().isInterrupted()) return response;
                    manager.applyExtractionRulesIfCurrent(plan.profileId(), plan.authenticationGeneration(),
                            SessionTrafficContexts.fromRequest(request, run.options.tool()),
                            dev.nquangit.corestra.integration.HttpMessageSnapshots.request(request, rules),
                            dev.nquangit.corestra.integration.HttpMessageSnapshots.response(response, rules));
                } catch (RuntimeException ignored) { /* Extraction must not discard a response already received. */ }
            }
            return response;
        } finally { SessionGuard.exit(); }
    }

}
