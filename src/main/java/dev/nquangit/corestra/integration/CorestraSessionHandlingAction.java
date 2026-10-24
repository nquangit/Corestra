package dev.nquangit.corestra.integration;

import burp.api.montoya.http.sessions.ActionResult;
import burp.api.montoya.http.sessions.SessionHandlingAction;
import burp.api.montoya.http.sessions.SessionHandlingActionData;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.session.SessionGuard;
import dev.nquangit.corestra.interaction.InteractionAbortedException;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Montoya session-handling action that runs configured prerequisite flows before Burp issues a
 * request under a session-handling rule.
 *
 * <p>When Burp invokes the action, every flow that {@link SessionHandlingFlowMapper opts into}
 * session handling is executed (e.g. to refresh tokens or cookies). The base request is then
 * returned unchanged via {@link ActionResult}; flows affect session state through the side effects
 * of the requests they send (Burp's cookie jar, server state), never through hidden mutation of the
 * live base request.</p>
 *
 * <p>Collaborators are injected as plain functional types so the orchestration is unit-testable
 * without a live Burp runtime: a {@link Supplier} of the current flows and a {@link Consumer} that
 * runs one flow (in production, {@code RunFlowAction::run}).</p>
 */
public final class CorestraSessionHandlingAction implements SessionHandlingAction {

    // Montoya's native session action has no drop operation. The project HTTP handler
    // consumes this marker and drops the parent request before any transport send.
    static final String INTERACTION_ABORT_HEADER = "X-Corestra-Interaction-Aborted";
    private static final String NAME = "Corestra: run prerequisite flows";

    private final Supplier<List<FlowDefinition>> flowsSupplier;
    private final Consumer<FlowDefinition> flowRunner;
    private final SessionHandlingFlowMapper mapper;

    /**
     * Creates a session-handling action.
     *
     * @param flowsSupplier supplies the current set of defined flows
     * @param flowRunner    runs a single flow (blocking); in production {@code RunFlowAction::run}
     * @param mapper        selects which flows are applicable to session handling
     * @throws NullPointerException if any argument is {@code null}
     */
    public CorestraSessionHandlingAction(
            Supplier<List<FlowDefinition>> flowsSupplier,
            Consumer<FlowDefinition> flowRunner,
            SessionHandlingFlowMapper mapper) {
        this.flowsSupplier = Objects.requireNonNull(flowsSupplier, "flowsSupplier");
        this.flowRunner = Objects.requireNonNull(flowRunner, "flowRunner");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ActionResult performAction(SessionHandlingActionData actionData) {
        if (SessionGuard.isActive() || AutoTriggerHttpHandler.hasProxyPipelineMarker(actionData.request()))
            return ActionResult.actionResult(actionData.request());
        try { runApplicableFlows(); }
        catch (InteractionAbortedException exception) {
            return ActionResult.actionResult(actionData.request().withHeader(INTERACTION_ABORT_HEADER, "true"));
        }
        // Return the base request unchanged — session state is refreshed via the flows' own sends.
        return ActionResult.actionResult(actionData.request());
    }

    /**
     * Runs every applicable flow once, isolating failures so one failing flow does not prevent the
     * others (or the live request) from proceeding.
     */
    void runApplicableFlows() {
        if (SessionGuard.isActive()) return;
        for (FlowDefinition flow : mapper.applicableFlows(flowsSupplier.get())) {
            SessionGuard.enter();
            try {
                flowRunner.accept(flow);
            } catch (InteractionAbortedException exception) {
                throw exception;
            } catch (RuntimeException ignored) {
                // A prerequisite flow failure must not break the user's live request.
            } finally {
                SessionGuard.exit();
            }
        }
    }
}
