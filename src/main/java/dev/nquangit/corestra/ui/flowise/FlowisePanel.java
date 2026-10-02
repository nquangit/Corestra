package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.flow.execution.FlowExecutionLog;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.util.Objects;

/**
 * The main Flowise editor panel registered as the first root tab.
 *
 * <p>Switches between an empty-state card (with a «+ New Flow» button) and a
 * {@link FlowTabsPanel} card once flows exist.  All per-flow actions (Rename, Duplicate,
 * Delete, Change Color) are accessible via the right-click context menu on each tab title.
 * New flows can also be added via the {@code +} pseudo-tab at the end of the tab strip.</p>
 */
public final class FlowisePanel extends JPanel {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABS  = "tabs";

    private final FlowWorkspaceController controller;
    private final FlowTabsPanel flowTabsPanel;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;

    /**
     * Creates the Flowise editor panel wired to the supplied controller.
     *
     * @param controller the workspace controller that owns flow state
     * @param api        the Montoya API used to create Burp-native editors
     * @throws NullPointerException if either argument is {@code null}
     */
    public FlowisePanel(FlowWorkspaceController controller, MontoyaApi api) {
        this(controller, api, null, new FlowExecutionLog());
    }

    /**
     * Creates the Flowise editor panel wired to the supplied controllers.
     *
     * @param controller the workspace controller that owns flow state
     * @param api the Montoya API used to create Burp-native editors
     * @param accessController optional Access Testing controller for workflow actions
     * @throws NullPointerException if controller or api is {@code null}
     */
    public FlowisePanel(
            FlowWorkspaceController controller,
            MontoyaApi api,
            AccessUiController accessController) {
        this(controller, api, accessController, new FlowExecutionLog());
    }

    /** Creates Flowise with a shared execution timeline. */
    public FlowisePanel(
            FlowWorkspaceController controller,
            MontoyaApi api,
            AccessUiController accessController,
            FlowExecutionLog executionLog) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(api, "api");

        flowTabsPanel = new FlowTabsPanel(controller, api, accessController,
                Objects.requireNonNull(executionLog, "executionLog"));
        cardLayout = new CardLayout();
        cardPanel  = new JPanel(cardLayout);
        cardPanel.add(buildEmptyState(), CARD_EMPTY);
        cardPanel.add(flowTabsPanel,     CARD_TABS);

        add(cardPanel, BorderLayout.CENTER);

        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private JPanel buildEmptyState() {
        JLabel title = new JLabel("Build your first HTTP flow", SwingConstants.CENTER);
        Font base = UIManager.getFont("Label.font");
        if (base != null) title.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 2f));

        JLabel hint = new JLabel(
                "<html><div style='text-align:center'>Capture a request from Burp or create a flow, then add extraction, "
                        + "injection, conditions, and execution rules.</div></html>",
                SwingConstants.CENTER);
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));

        StyledButton createBtn = StyledButton.primary("+ New Flow");
        createBtn.setToolTipText("Create the first flow");
        createBtn.addActionListener(e -> addFlow());

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);
        title.setAlignmentX(0.5f);
        hint.setAlignmentX(0.5f);
        createBtn.setAlignmentX(0.5f);
        inner.add(title);
        inner.add(Box.createVerticalStrut(6));
        inner.add(hint);
        inner.add(Box.createVerticalStrut(14));
        inner.add(createBtn);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(inner);
        return panel;
    }

    private void refresh() {
        cardLayout.show(cardPanel, controller.flows().isEmpty() ? CARD_EMPTY : CARD_TABS);
    }

    private void addFlow() {
        flowTabsPanel.addFlow();
    }
}
