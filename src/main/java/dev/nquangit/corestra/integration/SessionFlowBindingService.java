package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.session.SessionBindingException;
import dev.nquangit.corestra.session.SessionExecutionBinding;
import dev.nquangit.corestra.session.SessionInjectionFingerprint;
import dev.nquangit.corestra.session.SessionInjectionPlan;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionRequestSession;
import dev.nquangit.corestra.session.SessionTrafficContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Carries the initiating session across trigger phases and validates every actual send. */
public final class SessionFlowBindingService {
    private final SessionManager manager;
    private final InjectionService injections;
    private final IntFunction<Optional<SessionRequestSession>> liveSessions;

    /** Creates a binding boundary using the session handler's bounded live-request registry. */
    public SessionFlowBindingService(SessionManager manager, InjectionService injections,
                                     IntFunction<Optional<SessionRequestSession>> liveSessions) {
        this.manager = Objects.requireNonNull(manager);
        this.injections = Objects.requireNonNull(injections);
        this.liveSessions = Objects.requireNonNull(liveSessions);
    }

    /** Finds the session captured for this exact Burp message ID, never by host/path or thread. */
    public Optional<SessionRequestSession> liveSession(int messageId) { return liveSessions.apply(messageId); }

    /** Binds an execution after checking that the initiating session is still the same valid session. */
    public ExecutionContext bind(ExecutionContext context, SessionRequestSession source, StepTriggerRule rule) {
        try {
            SessionTrafficContext origin = source.originalTraffic();
            SessionInjectionPlan current = manager.prepareBoundSession(source.profileId(), origin);
            if (!fingerprint(current).equals(source.fingerprint())) throw new SessionBindingException("Initiating session changed before the flow started");
            String host = origin.host();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            List<String> allowed = new ArrayList<>();
            allowed.add(SessionExecutionBinding.normalizeOrigin(origin.scheme() + "://" + host + ":" + origin.port()));
            allowed.addAll(rule.sessionOrigins());
            var binding = new SessionExecutionBinding(current.profileId(), current.authenticationGeneration(),
                    fingerprint(current), origin.toolTypeName(), allowed);
            Map<String, String> metadata = new LinkedHashMap<>(context.metadata());
            metadata.putAll(binding.metadata());
            return new ExecutionContext(context.runId(), context.flowId(), metadata, context.variables(),
                    context.aggregationBuffer(), context.stepHistory(), context.startedAtEpochMillis(), context.completedAtEpochMillis());
        } catch (SessionBindingException exception) { throw exception; }
        catch (RuntimeException exception) { throw new SessionBindingException("Could not bind the initiating session"); }
    }

    /** Validates destination, enabled state, TTL, complete rules and auth epoch without re-login. */
    public SessionInjectionPlan validate(ExecutionContext context, HttpRequest request) {
        var binding = SessionExecutionBinding.read(context);
        SessionTrafficContext traffic = SessionTrafficContexts.fromRequest(request, binding.tool());
        binding.requireDestination(traffic);
        SessionInjectionPlan plan = manager.prepareBoundSession(binding.profileId(), traffic);
        if (plan.authenticationGeneration() != binding.generation() || !fingerprint(plan).equals(binding.fingerprint())) {
            throw new SessionBindingException("Authentication changed during this flow; start a new run with the current session");
        }
        return plan;
    }

    /** Applies the bound session after ordinary step injection, so stored auth cannot override it. */
    public HttpRequest apply(ExecutionContext context, HttpRequest request) {
        try {
            var plan = validate(context, request);
            var mutable = new InjectionService.MutableHttpRequest(request.method() + " " + request.path() + " "
                    + request.httpVersion(), request.headers().stream().map(h -> h.name() + ": " + h.value()).toList(), request.bodyToString());
            HttpRequest prepared = HttpRequestMutationAdapter.apply(request, injections.applyRequired(plan.rules(), Map.of(), mutable));
            validate(context, prepared);
            return prepared;
        } catch (SessionBindingException exception) { throw exception; }
        catch (RuntimeException exception) { throw new SessionBindingException("Bound session injection failed; request was not sent"); }
    }

    private String fingerprint(SessionInjectionPlan plan) { return SessionInjectionFingerprint.of(plan); }
}
