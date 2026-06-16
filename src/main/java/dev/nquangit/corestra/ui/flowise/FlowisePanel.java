package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.ui.access.AccessUiController;
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
        this(controller, api, null);
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
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(api, "api");

        flowTabsPanel = new FlowTabsPanel(controller, api, accessController);
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
        JLabel hint = new JLabel("No flows yet.", SwingConstants.CENTER);
        Font base = UIManager.getFont("Label.font");
        if (base != null) hint.setFont(base.deriveFont(Font.ITALIC));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));

        StyledButton createBtn = StyledButton.primary("+ New Flow");
        createBtn.setToolTipText("Create the first flow");
        createBtn.addActionListener(e -> addFlow());

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);
        hint.setAlignmentX(0.5f);
        createBtn.setAlignmentX(0.5f);
        inner.add(hint);
        inner.add(Box.createVerticalStrut(10));
        inner.add(createBtn);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    private void refresh() {
        cardLayout.show(cardPanel, controller.flows().isEmpty() ? CARD_EMPTY : CARD_TABS);
    }

    private void addFlow() {
        flowTabsPanel.addFlow();
    }
}
