package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the {@link HttpRequestEditor} and {@link HttpResponseEditor} in sync with the step
 * currently selected in {@link FlowWorkspaceController}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Converts a {@link StoredRequestTemplate} to a Montoya {@link HttpRequest} and calls
 *       {@link HttpRequestEditor#setRequest} when the selected step changes.</li>
 *   <li>Clears the response editor when the selected step changes.</li>
 *   <li>Provides {@link #captureToStep()} to read the current editor state and persist it as
 *       the selected step's request template.</li>
 *   <li>Provides {@link #sendToRepeater()} to send the selected step's request to Burp Repeater.</li>
 * </ul>
 *
 * <p>This class is not thread-safe. All calls should be made on the Swing Event Dispatch Thread.</p>
 */
public final class RequestResponseController {

    private final FlowWorkspaceController workspaceController;
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final MontoyaApi api;

    /**
     * Creates a controller that wires the supplied editors to the workspace controller.
     *
     * @param workspaceController the controller owning flow and step selection state
     * @param requestEditor       the Burp-native request editor to keep in sync
     * @param responseEditor      the Burp-native response editor to keep in sync
     * @param api                 the Montoya API used for Repeater integration
     * @throws NullPointerException if any argument is {@code null}
     */
    public RequestResponseController(
            FlowWorkspaceController workspaceController,
            HttpRequestEditor requestEditor,
            HttpResponseEditor responseEditor,
            MontoyaApi api) {
        this.workspaceController = Objects.requireNonNull(workspaceController, "workspaceController");
        this.requestEditor = Objects.requireNonNull(requestEditor, "requestEditor");
        this.responseEditor = Objects.requireNonNull(responseEditor, "responseEditor");
        this.api = Objects.requireNonNull(api, "api");

        workspaceController.addFlowChangeListener(this::refresh);
        refresh();
    }

    /**
     * Reads the current request from the editor and persists it as the selected step's template.
     *
     * <p>Does nothing when no step is selected or when the request editor has not been modified.</p>
     */
    public void captureToStep() {
        FlowStep step = workspaceController.selectedStep();
        if (step == null) {
            return;
        }

        HttpRequest current = requestEditor.getRequest();
        if (current == null) {
            return;
        }

        StoredRequestTemplate updated = templateFromRequest(current);
        FlowStep updatedStep = step.withRequestTemplate(updated);

        FlowDefinition active = workspaceController.activeFlow();
        if (active == null) {
            return;
        }

        List<FlowStep> newSteps = new ArrayList<>(active.steps());
        for (int i = 0; i < newSteps.size(); i++) {
            if (newSteps.get(i).id().equals(step.id())) {
                newSteps.set(i, updatedStep);
                break;
            }
        }

        FlowDefinition updated2 = new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                newSteps, active.rules(), active.aggregation(), active.triggerConfig());
        workspaceController.updateFlow(updated2);
        workspaceController.setSelectedStep(updatedStep);
    }

    /**
     * Sends the selected step's request template to Burp Repeater.
     *
     * <p>Does nothing when no step is selected.</p>
     */
    public void sendToRepeater() {
        FlowStep step = workspaceController.selectedStep();
        if (step == null) {
            return;
        }

        HttpRequest request = templateToMontoyaRequest(step.requestTemplate());
        api.repeater().sendToRepeater(request, step.name());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void refresh() {
        FlowStep step = workspaceController.selectedStep();
        if (step == null) {
            requestEditor.setRequest(HttpRequest.httpRequest("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"));
            responseEditor.setResponse(HttpResponse.httpResponse(""));
            return;
        }

        requestEditor.setRequest(templateToMontoyaRequest(step.requestTemplate()));

        // Priority: in-memory captured response (same session) > persisted lastResponseRaw > empty.
        HttpResponse captured = workspaceController.getCapturedResponse(step.id());
        if (captured != null) {
            responseEditor.setResponse(captured);
        } else {
            HttpResponse persisted = decodeResponse(step.lastResponseRaw());
            responseEditor.setResponse(persisted != null ? persisted : HttpResponse.httpResponse(""));
        }
    }

    private static HttpResponse decodeResponse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            return HttpResponse.httpResponse(ByteArray.byteArray(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a {@link StoredRequestTemplate} to a Montoya {@link HttpRequest} suitable for
     * display in the request editor.
     */
    static HttpRequest templateToMontoyaRequest(StoredRequestTemplate template) {
        StringBuilder raw = new StringBuilder();
        raw.append(template.method()).append(" ").append(template.pathAndQuery()).append(" HTTP/1.1\r\n");
        // Host is stripped at capture time (stored in template fields); reconstruct it here.
        boolean defaultPort = ("https".equalsIgnoreCase(template.scheme()) && template.port() == 443)
                || ("http".equalsIgnoreCase(template.scheme()) && template.port() == 80);
        String hostValue = defaultPort ? template.host() : template.host() + ":" + template.port();
        raw.append("Host: ").append(hostValue).append("\r\n");
        for (String header : template.headerLines()) {
            raw.append(header).append("\r\n");
        }
        raw.append("\r\n");
        if (!template.body().isEmpty()) {
            raw.append(template.body());
        }

        boolean secure = "https".equalsIgnoreCase(template.scheme());
        HttpService service = HttpService.httpService(template.host(), template.port(), secure);
        return HttpRequest.httpRequest(service, raw.toString());
    }

    /**
     * Builds a {@link StoredRequestTemplate} from a Montoya {@link HttpRequest}.
     */
    private static StoredRequestTemplate templateFromRequest(HttpRequest request) {
        String method = request.method();
        String pathAndQuery = request.path();
        if (request.query() != null && !request.query().isEmpty()) {
            pathAndQuery = pathAndQuery + "?" + request.query();
        }

        String scheme = "https";
        String host = "example.com";
        int port = 443;
        if (request.httpService() != null) {
            host = request.httpService().host();
            port = request.httpService().port();
            scheme = request.httpService().secure() ? "https" : "http";
        }

        List<String> headers = new ArrayList<>();
        request.headers().forEach(h -> {
            if (!h.name().equalsIgnoreCase("host")) {
                headers.add(h.toString());
            }
        });

        String body = request.bodyToString();
        return new StoredRequestTemplate(method, scheme, host, port, pathAndQuery, headers, body);
    }
}
