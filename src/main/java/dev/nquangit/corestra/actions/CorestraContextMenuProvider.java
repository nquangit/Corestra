package dev.nquangit.corestra.actions;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provides Corestra items in Burp's right-click context menus throughout the suite.
 *
 * <p>When one or more requests are selected the provider inserts an
 * <b>Add to Corestra Flow</b> submenu. The submenu contains one entry per defined flow;
 * selecting an entry calls {@link CaptureRequestIntoFlowAction#capture} for every selected
 * request, appending a new step to that flow.</p>
 *
 * <p>Register via {@code api.userInterface().registerContextMenuItemsProvider(provider)}
 * during extension bootstrap.</p>
 */
public final class CorestraContextMenuProvider implements ContextMenuItemsProvider {

    private final FlowWorkspaceController controller;
    private final AccessUiController accessController;
    private final CaptureRequestIntoFlowAction captureAction;

    /**
     * Creates the context menu provider.
     *
     * @param controller the workspace controller that owns the list of flows
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public CorestraContextMenuProvider(FlowWorkspaceController controller) {
        this(controller, null);
    }

    /**
     * Creates the context menu provider with optional Access Testing actions.
     *
     * @param controller the workspace controller that owns the list of flows
     * @param accessController optional Access Testing controller
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public CorestraContextMenuProvider(FlowWorkspaceController controller, AccessUiController accessController) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.accessController = accessController;
        this.captureAction = new CaptureRequestIntoFlowAction(controller);
    }

    /**
     * Returns the Corestra submenu when at least one request is available in the event.
     *
     * <p>Returns an empty list when:
     * <ul>
     *   <li>No request is available in the context menu event.</li>
     *   <li>No flows are defined in the controller.</li>
     * </ul>
     *
     * @param event the context menu event from Burp
     * @return a list containing the top-level Corestra menu, or an empty list
     */
    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> pairs = collectRequestResponses(event);
        if (pairs.isEmpty()) {
            return List.of();
        }

        if (controller.flows().isEmpty() && accessController == null) {
            return List.of();
        }

        List<FlowDefinition> flows = controller.flows();
        JMenu root = new JMenu("Corestra");

        if (accessController != null) {
            JMenuItem addToTemplates = new JMenuItem("Add to Access Templates");
            addToTemplates.addActionListener(e -> addToAccessTemplates(pairs));
            root.add(addToTemplates);
            if (!flows.isEmpty()) {
                root.addSeparator();
            }
        }

        if (flows.isEmpty()) {
            return List.of(root);
        }

        JMenu addToFlowMenu = new JMenu("Add to Corestra Flow");

        for (FlowDefinition flow : flows) {
            String label = flow.name() + "  (" + flow.steps().size() + " steps)";
            JMenuItem item = new JMenuItem(label);
            String flowId = flow.id();
            item.addActionListener(e -> {
                for (HttpRequestResponse pair : pairs) {
                    captureAction.capture(flowId, pair);
                }
            });
            addToFlowMenu.add(item);
        }

        // Convenience: also offer "Add to active flow" at the top when one is selected.
        FlowDefinition active = controller.activeFlow();
        if (active != null) {
            JMenuItem quickItem = new JMenuItem("Add to Active Flow  [" + active.name() + "]");
            String activeId = active.id();
            quickItem.addActionListener(e -> {
                for (HttpRequestResponse pair : pairs) {
                    captureAction.capture(activeId, pair);
                }
            });

            root.add(quickItem);
            root.addSeparator();
            root.add(addToFlowMenu);
            return List.of(root);
        }

        root.add(addToFlowMenu);
        return List.of(root);
    }

    private void addToAccessTemplates(List<HttpRequestResponse> pairs) {
        int created = 0;
        for (HttpRequestResponse pair : pairs) {
            StoredRequestTemplate request = CaptureRequestIntoFlowAction.toTemplate(pair.request());
            AccessRequestTemplate template = accessController.saveCapturedRequestTemplate(
                    request.method() + " " + request.pathAndQuery(),
                    request,
                    OperationMetadata.unknown());
            if (template != null) {
                created++;
            }
        }
        JOptionPane.showMessageDialog(
                null,
                "Created " + created + " disabled Access Template" + (created == 1 ? "." : "s."),
                "Corestra Access Templates",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static List<HttpRequestResponse> collectRequestResponses(ContextMenuEvent event) {
        List<HttpRequestResponse> result = new ArrayList<>();

        // Single request/response from a message editor (Repeater, Proxy intercept, etc.)
        event.messageEditorRequestResponse().ifPresent(editor -> {
            HttpRequestResponse rr = editor.requestResponse();
            if (rr != null && rr.request() != null) {
                result.add(rr);
            }
        });

        // Multi-selection (Proxy history, Logger, etc.)
        if (result.isEmpty()) {
            for (HttpRequestResponse rr : event.selectedRequestResponses()) {
                if (rr.request() != null) {
                    result.add(rr);
                }
            }
        }

        return result;
    }
}
