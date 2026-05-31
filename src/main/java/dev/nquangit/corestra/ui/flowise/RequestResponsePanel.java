package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Embeds a Burp-native request editor (editable) and response editor (read-only) side by side.
 *
 * <p>A step-header bar sits above the editors showing the selected step's name (editable)
 * and an enabled toggle. Changes are saved back through the {@link FlowWorkspaceController}.</p>
 *
 * <p>The toolbar above the header provides:
 * <ul>
 *   <li><b>Capture to Step</b> — reads the current editor content and updates the selected step's
 *       request template via {@link RequestResponseController#captureToStep()}.</li>
 *   <li><b>Send to Repeater</b> — sends the selected step's request template to Burp Repeater
 *       via {@link RequestResponseController#sendToRepeater()}.</li>
 * </ul>
 */
public final class RequestResponsePanel extends JPanel {

    private final FlowWorkspaceController workspaceController;
    private final RequestResponseController reqRespController;

    public RequestResponsePanel(FlowWorkspaceController workspaceController, MontoyaApi api) {
        super(new BorderLayout());
        this.workspaceController = Objects.requireNonNull(workspaceController, "workspaceController");
        Objects.requireNonNull(api, "api");

        HttpRequestEditor requestEditor = api.userInterface().createHttpRequestEditor();
        HttpResponseEditor responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        reqRespController = new RequestResponseController(workspaceController, requestEditor, responseEditor, api);

        // Wrap both editors in panels with equal preferred widths.
        // JSplitPane.resetToPreferredSizes() places the divider at:
        //   leftPref + (available - leftPref - rightPref) * resizeWeight = available/2
        // When mainSplit later adjusts and gives this pane more space, resizeWeight(0.5)
        // distributes the delta equally — so the 50/50 split is preserved without any listeners.
        Dimension equalPref = new Dimension(100, 100);
        JPanel reqWrapper = new JPanel(new BorderLayout());
        reqWrapper.setPreferredSize(equalPref);
        reqWrapper.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        JPanel respWrapper = new JPanel(new BorderLayout());
        respWrapper.setPreferredSize(equalPref);
        respWrapper.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqWrapper, respWrapper);
        splitPane.setResizeWeight(0.5);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel northSection = new JPanel(new BorderLayout());
        northSection.add(buildToolbar(), BorderLayout.NORTH);
        northSection.add(buildStepHeader(), BorderLayout.SOUTH);

        add(northSection, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private JPanel buildToolbar() {
        StyledButton captureBtn = StyledButton.neutral("Capture to Step");
        captureBtn.setToolTipText("Update the selected step's request template from the editor");
        captureBtn.addActionListener(e -> reqRespController.captureToStep());

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(5, 4, 5, 4));
        bar.add(captureBtn);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private JPanel buildStepHeader() {
        JTextField nameField = new JTextField();
        nameField.setToolTipText("Step name — press Enter or click away to save");
        nameField.setEnabled(false);

        JCheckBox enabledBox = new JCheckBox("Enabled");
        enabledBox.setOpaque(false);
        enabledBox.setFocusPainted(false);
        enabledBox.setEnabled(false);

        // Sync fields from the controller whenever state changes.
        workspaceController.addFlowChangeListener(() -> {
            FlowStep step = workspaceController.selectedStep();
            FlowDefinition active = workspaceController.activeFlow();
            boolean valid = step != null && active != null
                    && active.steps().stream().anyMatch(s -> s.id().equals(step.id()));
            if (valid) {
                if (!nameField.getText().equals(step.name())) nameField.setText(step.name());
                enabledBox.setSelected(step.enabled());
                nameField.setEnabled(true);
                enabledBox.setEnabled(true);
            } else {
                nameField.setText("");
                nameField.setEnabled(false);
                enabledBox.setEnabled(false);
            }
        });

        nameField.addActionListener(e -> saveStepName(nameField.getText().strip()));
        nameField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { saveStepName(nameField.getText().strip()); }
        });
        enabledBox.addActionListener(e -> saveStepEnabled(enabledBox.isSelected()));

        Font base = UIManager.getFont("Label.font");
        JLabel lbl = new JLabel("Step: ");
        if (base != null) lbl.setFont(base.deriveFont(base.getSize2D() - 0.5f));
        lbl.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 4, 3, 6)));
        bar.add(lbl);
        bar.add(nameField);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(enabledBox);
        return bar;
    }

    private void saveStepName(String name) {
        FlowStep step = workspaceController.selectedStep();
        FlowDefinition active = workspaceController.activeFlow();
        if (step == null || active == null || name.isEmpty() || name.equals(step.name())) return;
        replaceStep(active, step.withName(name));
    }

    private void saveStepEnabled(boolean enabled) {
        FlowStep step = workspaceController.selectedStep();
        FlowDefinition active = workspaceController.activeFlow();
        if (step == null || active == null || step.enabled() == enabled) return;
        replaceStep(active, step.withEnabled(enabled));
    }

    private void replaceStep(FlowDefinition active, FlowStep updated) {
        List<FlowStep> steps = new ArrayList<>(active.steps());
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(updated.id())) { steps.set(i, updated); break; }
        }
        workspaceController.updateFlow(new FlowDefinition(active.id(), active.name(),
                active.description(), active.enabled(), steps, active.rules(),
                active.aggregation(), active.triggerConfig()));
        workspaceController.setSelectedStep(updated);
    }
}
