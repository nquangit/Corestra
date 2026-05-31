package dev.nquangit.corestra.actions;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts a Montoya {@link HttpRequest} into a new {@link FlowStep} and appends it to a
 * target {@link FlowDefinition} via the {@link FlowWorkspaceController}.
 *
 * <p>The host header is excluded from the stored header lines because it is already captured
 * in the {@link StoredRequestTemplate} service fields (host, port, scheme).</p>
 */
public final class CaptureRequestIntoFlowAction {

    private final FlowWorkspaceController controller;

    /**
     * Creates the action wired to the supplied controller.
     *
     * @param controller the workspace controller used to persist the updated flow
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public CaptureRequestIntoFlowAction(FlowWorkspaceController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    /**
     * Appends a new step built from {@code requestResponse} to the flow with the supplied id.
     *
     * <p>If the event carries a response it is stored transiently in the controller so the
     * response editor can display it immediately after the step is selected.</p>
     *
     * @param targetFlowId    the id of the flow to append the step to
     * @param requestResponse the Montoya request/response pair to capture
     * @throws IllegalArgumentException if no flow with {@code targetFlowId} exists in the controller
     * @throws NullPointerException     if either argument is {@code null}
     */
    public void capture(String targetFlowId, HttpRequestResponse requestResponse) {
        Objects.requireNonNull(targetFlowId, "targetFlowId");
        Objects.requireNonNull(requestResponse, "requestResponse");

        HttpRequest request = requestResponse.request();
        FlowDefinition target = findFlow(targetFlowId);
        StoredRequestTemplate template = toTemplate(request);
        String stepName = request.method() + " " + truncatePath(request.path());

        // Encode the response for persistence so it survives an extension reload.
        HttpResponse resp = requestResponse.response();
        String responseRaw = encodeResponse(resp);

        FlowStep newStep = new FlowStep(
                UUID.randomUUID().toString(),
                stepName,
                true,
                "",
                template,
                List.of(),
                List.of(),
                List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW,
                responseRaw,
                StepTriggerRule.fromTemplate(template));

        List<FlowStep> updatedSteps = new ArrayList<>(target.steps());
        updatedSteps.add(newStep);

        FlowDefinition updated = new FlowDefinition(
                target.id(),
                target.name(),
                target.description(),
                target.enabled(),
                updatedSteps,
                target.rules(),
                target.aggregation(),
                target.triggerConfig());

        controller.updateFlow(updated);
        // Also keep an in-memory copy so the editor shows it immediately in this session.
        controller.setCapturedResponse(newStep.id(), resp);
        controller.setActiveFlow(updated);
        controller.setSelectedStep(newStep);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FlowDefinition findFlow(String flowId) {
        for (FlowDefinition flow : controller.flows()) {
            if (flow.id().equals(flowId)) {
                return flow;
            }
        }
        throw new IllegalArgumentException("No flow found with id: " + flowId);
    }

    static StoredRequestTemplate toTemplate(HttpRequest request) {
        String host = "";
        int port = 443;
        boolean secure = true;

        if (request.httpService() != null) {
            host = request.httpService().host();
            port = request.httpService().port();
            secure = request.httpService().secure();
        }

        String scheme = secure ? "https" : "http";
        // path() already returns the full path including query string per the Montoya API contract.
        String pathAndQuery = request.path();
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            pathAndQuery = "/";
        }

        // Exclude the Host header — it is stored separately in the template fields.
        List<String> headers = new ArrayList<>();
        for (HttpHeader h : request.headers()) {
            if (!h.name().equalsIgnoreCase("host")) {
                headers.add(h.toString());
            }
        }

        // Use a placeholder host when the service is unknown (e.g. pasted raw requests).
        if (host.isEmpty()) {
            host = "example.com";
        }

        return new StoredRequestTemplate(
                request.method(),
                scheme,
                host,
                port,
                pathAndQuery,
                headers,
                request.bodyToString());
    }

    private static String truncatePath(String path) {
        if (path == null) return "/";
        return path.length() > 40 ? path.substring(0, 40) + "…" : path;
    }

    static String encodeResponse(HttpResponse response) {
        if (response == null) return null;
        try {
            return Base64.getEncoder().encodeToString(response.toByteArray().getBytes());
        } catch (Exception e) {
            return null;
        }
    }
}
