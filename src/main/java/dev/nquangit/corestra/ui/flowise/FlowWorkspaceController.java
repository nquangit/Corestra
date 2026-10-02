package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.persistence.FlowRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Controller that owns the active flow, selected step, and the in-memory list of flow
 * definitions for the Flowise editor.
 *
 * <p>All UI state changes (active flow, selected step) must go through this controller
 * rather than being stored in individual panels. Interested panels register a
 * {@link Runnable} via {@link #addFlowChangeListener(Runnable)} and are notified
 * after each state mutation so they can refresh their own display.</p>
 *
 * <p>This class is not thread-safe. All calls should be made on the Swing Event
 * Dispatch Thread.</p>
 */
public final class FlowWorkspaceController {

    private final FlowRepository flowRepository;
    private final FlowEngine flowEngine;
    private final List<FlowDefinition> flows;
    private final List<Runnable> changeListeners;

    private FlowDefinition activeFlow;
    private FlowStep selectedStep;
    private ExecutionResult lastExecutionResult;
    // Transient per-step responses captured from Burp context menus (not persisted).
    private final Map<String, HttpResponse> capturedResponses = new HashMap<>();

    /**
     * Creates a controller and eagerly loads all persisted flows from the repository.
     *
     * @param flowRepository the repository used to load and save flow definitions
     * @throws NullPointerException if {@code flowRepository} is {@code null}
     */
    public FlowWorkspaceController(FlowRepository flowRepository) {
        this(flowRepository, null);
    }

    /**
     * Creates a controller wired to a flow engine for explicit execution.
     *
     * @param flowRepository the repository used to load and save flow definitions
     * @param flowEngine     the engine used to run flows, or {@code null} when execution is unavailable
     * @throws NullPointerException if {@code flowRepository} is {@code null}
     */
    public FlowWorkspaceController(FlowRepository flowRepository, FlowEngine flowEngine) {
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.flowEngine = flowEngine;
        this.flows = new ArrayList<>(flowRepository.loadAll());
        this.changeListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Returns the flow engine used for explicit execution, or {@code null} when none is wired.
     *
     * @return the flow engine, or {@code null}
     */
    public FlowEngine flowEngine() {
        return flowEngine;
    }

    /**
     * Returns an unmodifiable snapshot of the current flow list.
     *
     * @return the current list of flow definitions
     */
    public List<FlowDefinition> flows() {
        return List.copyOf(flows);
    }

    /**
     * Returns the currently active flow, or {@code null} when no flow is selected.
     *
     * @return the active flow definition, or {@code null}
     */
    public FlowDefinition activeFlow() {
        return activeFlow;
    }

    /**
     * Sets the active flow and notifies all registered change listeners.
     *
     * <p>The first step is selected automatically so the request/response editors are useful as
     * soon as the user enters a flow. Passing {@code null}, or selecting an empty flow, clears the
     * step selection.</p>
     *
     * @param flow the flow definition to make active, or {@code null} to clear the selection
     */
    public void setActiveFlow(FlowDefinition flow) {
        this.activeFlow = flow;
        this.selectedStep = flow == null || flow.steps().isEmpty()
                ? null
                : flow.steps().getFirst();
        notifyListeners();
    }

    /**
     * Returns the currently selected step, or {@code null} when no step is selected.
     *
     * @return the selected flow step, or {@code null}
     */
    public FlowStep selectedStep() {
        return selectedStep;
    }

    /**
     * Sets the selected step and notifies all registered change listeners.
     *
     * <p>Passing {@code null} clears the step selection.</p>
     *
     * @param step the step to select, or {@code null} to clear the selection
     */
    public void setSelectedStep(FlowStep step) {
        this.selectedStep = step;
        notifyListeners();
    }

    /**
     * Adds a flow to the in-memory list, persists the updated list, and notifies listeners.
     *
     * @param flow the flow definition to add
     * @throws NullPointerException if {@code flow} is {@code null}
     */
    public void addFlow(FlowDefinition flow) {
        Objects.requireNonNull(flow, "flow");
        flows.add(flow);
        persist();
        notifyListeners();
    }

    /**
     * Removes a flow from the in-memory list, persists the updated list, and notifies listeners.
     *
     * <p>If the removed flow was the active flow the active selection and step selection are
     * cleared.</p>
     *
     * @param flow the flow definition to remove
     * @throws NullPointerException if {@code flow} is {@code null}
     */
    public void removeFlow(FlowDefinition flow) {
        Objects.requireNonNull(flow, "flow");
        flows.remove(flow);
        if (Objects.equals(activeFlow, flow)) {
            activeFlow = null;
            selectedStep = null;
        }
        persist();
        notifyListeners();
    }

    /**
     * Replaces an existing flow with a new version, persists the list, and notifies listeners.
     *
     * <p>Replacement is done by matching on {@link FlowDefinition#id()}. If the flow to
     * replace is the active flow the active reference is updated to the new version.</p>
     *
     * @param updated the updated flow definition
     * @throws NullPointerException     if {@code updated} is {@code null}
     * @throws IllegalArgumentException if no flow with the same id exists in the list
     */
    public void updateFlow(FlowDefinition updated) {
        Objects.requireNonNull(updated, "updated");
        int index = indexOfFlowById(updated.id());
        if (index < 0) {
            throw new IllegalArgumentException("No flow found with id: " + updated.id());
        }
        flows.set(index, updated);
        if (activeFlow != null && activeFlow.id().equals(updated.id())) {
            activeFlow = updated;
            // Freshen selectedStep so panels read the latest field values (e.g. lastResponseRaw
            // updated by background aggregation) rather than a stale snapshot.
            if (selectedStep != null) {
                updated.steps().stream()
                        .filter(s -> s.id().equals(selectedStep.id()))
                        .findFirst()
                        .ifPresent(s -> selectedStep = s);
            }
        }
        persist();
        notifyListeners();
    }

    /**
     * Returns the most recent execution result for the active flow, or {@code null} when no
     * execution has been run in this session.
     *
     * @return the last execution result, or {@code null}
     */
    public ExecutionResult lastExecutionResult() {
        return lastExecutionResult;
    }

    /**
     * Stores a response captured from Burp (e.g. via the context menu) against its step id.
     * The response is held in memory only and is not persisted.
     *
     * @param stepId   the id of the step the response belongs to
     * @param response the captured response; ignored when {@code null}
     */
    public void setCapturedResponse(String stepId, HttpResponse response) {
        if (stepId != null && response != null) {
            capturedResponses.put(stepId, response);
        }
    }

    /**
     * Returns the captured response for the given step id, or {@code null} if none exists.
     *
     * @param stepId the step id to look up
     * @return the captured response or {@code null}
     */
    public HttpResponse getCapturedResponse(String stepId) {
        return stepId == null ? null : capturedResponses.get(stepId);
    }

    /**
     * Stores the execution result and notifies all registered change listeners.
     *
     * @param result the execution result to store; may be {@code null} to clear the last result
     */
    public void setLastExecutionResult(ExecutionResult result) {
        this.lastExecutionResult = result;
        notifyListeners();
    }

    /**
     * Registers a listener that is notified after any state change.
     *
     * <p>The listener is invoked on the same thread that caused the state change —
     * callers should ensure listeners perform only safe EDT operations.</p>
     *
     * @param listener the runnable to invoke on state changes
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public void addFlowChangeListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void persist() {
        flowRepository.saveAll(List.copyOf(flows));
    }

    private void notifyListeners() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private int indexOfFlowById(String id) {
        for (int i = 0; i < flows.size(); i++) {
            if (flows.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
