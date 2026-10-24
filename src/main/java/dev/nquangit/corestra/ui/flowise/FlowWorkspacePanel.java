package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.execution.FlowExecutionLog;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The content panel for a single flow tab inside the Flowise editor.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>Left:</b> {@link StepListPanel} — ordered, drag-and-drop step list with enable/disable.</li>
 *   <li><b>Right:</b> vertically split detail area:
 *     <ul>
 *       <li><b>Top:</b> {@link RequestResponsePanel} — Burp-native request/response editors.</li>
 *       <li><b>Bottom:</b> Compact tab strip with Rules, Execution, Aggregation, and Notes tabs.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class FlowWorkspacePanel extends JPanel {

    private static final int MAX_DEFAULT_DETAIL_HEIGHT = 210;
    private static final int MIN_DEFAULT_DETAIL_HEIGHT = 130;
    private final JTabbedPane detailTabs;
    private final JSplitPane rightSplit;
    private final JSplitPane mainSplit;

    /**
     * Creates a workspace panel for one flow, wired to the supplied controller.
     *
     * @param controller the workspace controller that owns flow and step selection state
     * @param api        the Montoya API used to create Burp-native editors
     * @throws NullPointerException if either argument is {@code null}
     */
    public FlowWorkspacePanel(FlowWorkspaceController controller, MontoyaApi api) {
        this(controller, api, null, new FlowExecutionLog());
    }

    /**
     * Creates a workspace panel for one flow, wired to the supplied controllers.
     *
     * @param controller the workspace controller that owns flow and step selection state
     * @param api the Montoya API used to create Burp-native editors
     * @param accessController optional Access Testing controller for workflow actions
     * @throws NullPointerException if controller or api is {@code null}
     */
    public FlowWorkspacePanel(
            FlowWorkspaceController controller,
            MontoyaApi api,
            AccessUiController accessController) {
        this(controller, api, accessController, new FlowExecutionLog());
    }

    /** Creates a workspace panel with a shared execution timeline. */
    public FlowWorkspacePanel(
            FlowWorkspaceController controller,
            MontoyaApi api,
            AccessUiController accessController,
            FlowExecutionLog executionLog) {
        super(new BorderLayout());
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(api, "api");

        StepListPanel stepList = new StepListPanel(controller, accessController);
        RequestResponsePanel reqResp = new RequestResponsePanel(controller, api);
        detailTabs = buildDetailTabs(controller, api, reqResp, accessController,
                Objects.requireNonNull(executionLog, "executionLog"));

        rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, reqResp, detailTabs);
        rightSplit.setName("flowRequestDetailSplit");
        rightSplit.setResizeWeight(1.0);
        rightSplit.setOneTouchExpandable(true);
        rightSplit.setDividerSize(7);
        rightSplit.setBorder(BorderFactory.createEmptyBorder());
        rightSplit.addComponentListener(new ComponentAdapter() {
            private boolean positioned;

            @Override
            public void componentResized(ComponentEvent event) {
                if (!positioned && rightSplit.getHeight() > 0) {
                    rightSplit.setDividerLocation(defaultDetailDividerLocation(rightSplit.getHeight()));
                    positioned = true;
                }
            }
        });

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stepList, rightSplit);
        mainSplit.setDividerSize(4);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        // Set initial divider to ~30 % of the actual panel width on first layout.
        mainSplit.addComponentListener(new ComponentAdapter() {
            private boolean set = false;
            @Override public void componentResized(ComponentEvent e) {
                if (!set && mainSplit.getWidth() > 0) {
                    mainSplit.setDividerLocation((int) (mainSplit.getWidth() * 0.30));
                    set = true;
                }
            }
        });

        add(mainSplit, BorderLayout.CENTER);
    }

    // Preserve per-flow view preferences without retaining another native editor workspace.
    record ViewState(int detailTab, int detailDivider, int stepsDivider) { }

    ViewState viewState() {
        return new ViewState(detailTabs.getSelectedIndex(), rightSplit.getDividerLocation(), mainSplit.getDividerLocation());
    }

    void restoreViewState(ViewState state) {
        detailTabs.setSelectedIndex(state == null ? 0 : state.detailTab());
        if (state != null) {
            rightSplit.setDividerLocation(state.detailDivider());
            mainSplit.setDividerLocation(state.stepsDivider());
        } else {
            if (rightSplit.getHeight() > 0) rightSplit.setDividerLocation(defaultDetailDividerLocation(rightSplit.getHeight()));
            if (mainSplit.getWidth() > 0) mainSplit.setDividerLocation((int) (mainSplit.getWidth() * 0.30));
        }
    }

    static int defaultDetailDividerLocation(int availableHeight) {
        int proportionalHeight = (int) Math.round(availableHeight * 0.26);
        int detailHeight = Math.min(MAX_DEFAULT_DETAIL_HEIGHT,
                Math.max(MIN_DEFAULT_DETAIL_HEIGHT, proportionalHeight));
        return Math.max(0, availableHeight - detailHeight);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static JTabbedPane buildDetailTabs(
            FlowWorkspaceController controller,
            MontoyaApi api,
            RequestResponsePanel requestResponsePanel,
            AccessUiController accessController,
            FlowExecutionLog executionLog) {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);

        tabs.addTab("Rules", new RuleSummaryPanel(
                controller, requestResponsePanel::responseRegexSelection, accessController));
        tabs.addTab("Conditions & Actions", new ConditionActionPanel(controller));
        tabs.addTab("Execution", new ExecutionPanel(controller, accessController));
        tabs.addTab("Flow Log", new FlowLogPanel(controller, executionLog, api));
        tabs.addTab("Auto Trigger Flow", new TriggerPanel(controller));
        tabs.addTab("Aggregation", new AggregationPanel(controller));
        tabs.addTab("Notes", buildNotesPanel(controller));

        // Slightly smaller font for the detail tab strip to visually subordinate it
        java.awt.Font tabFont = tabs.getFont();
        if (tabFont != null) {
            tabs.setFont(tabFont.deriveFont(tabFont.getSize2D() - 1f));
        }

        return tabs;
    }

    private static JPanel buildNotesPanel(FlowWorkspaceController controller) {
        JTextArea notes = new JTextArea();
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setEditable(true);

        controller.addFlowChangeListener(() -> {
            FlowStep step = controller.selectedStep();
            String text = step != null ? step.notes() : "";
            if (!notes.getText().equals(text)) notes.setText(text);
        });

        StyledButton saveBtn = StyledButton.primary("Save Notes");
        saveBtn.setToolTipText("Persist the notes text to the selected step");
        saveBtn.addActionListener(e -> {
            FlowStep step = controller.selectedStep();
            FlowDefinition active = controller.activeFlow();
            if (step == null || active == null) return;
            FlowStep updated = step.withNotes(notes.getText());
            List<FlowStep> steps = new ArrayList<>(active.steps());
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).id().equals(updated.id())) { steps.set(i, updated); break; }
            }
            controller.updateFlow(new FlowDefinition(active.id(), active.name(), active.description(),
                    active.enabled(), steps, active.rules(), active.aggregation(), active.triggerConfig()));
            controller.setSelectedStep(updated);
        });

        JPanel btnBar = new JPanel();
        btnBar.setLayout(new BoxLayout(btnBar, BoxLayout.X_AXIS));
        btnBar.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        btnBar.add(saveBtn);
        btnBar.add(Box.createHorizontalGlue());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        panel.add(new JScrollPane(notes), BorderLayout.CENTER);
        panel.add(btnBar, BorderLayout.SOUTH);
        return panel;
    }
}
