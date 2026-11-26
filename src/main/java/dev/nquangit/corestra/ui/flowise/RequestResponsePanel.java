package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.RegexSelection;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.ui.Selection;
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
import java.util.Optional;

/**
 * Embeds a Burp-native read-only request viewer and read-only response viewer side by side.
 *
 * <p>A step-header bar sits above the editors showing the selected step's name (editable)
 * and an enabled toggle. Changes are saved back through the {@link FlowWorkspaceController}.</p>
 */
public final class RequestResponsePanel extends JPanel {

    private final FlowWorkspaceController workspaceController;
    private final RequestResponseController reqRespController;
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;

    public RequestResponsePanel(FlowWorkspaceController workspaceController, MontoyaApi api) {
        super(new BorderLayout());
        this.workspaceController = Objects.requireNonNull(workspaceController, "workspaceController");
        Objects.requireNonNull(api, "api");

        requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        reqRespController = new RequestResponseController(workspaceController, requestEditor, responseEditor, api);

        // Wrap both editors in panels with equal preferred widths.
        // JSplitPane.resetToPreferredSizes() places the divider at:
        //   leftPref + (available - leftPref - rightPref) * resizeWeight = available/2
        // When mainSplit later adjusts and gives this pane more space, resizeWeight(0.5)
        // distributes the delta equally — so the 50/50 split is preserved without any listeners.
        Dimension equalPref = new Dimension(100, 100);
        JPanel reqWrapper = new JPanel(new BorderLayout());
        reqWrapper.setPreferredSize(equalPref);
        reqWrapper.setMinimumSize(new Dimension(0, 0));
        reqWrapper.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        JPanel respWrapper = new JPanel(new BorderLayout());
        respWrapper.setPreferredSize(equalPref);
        respWrapper.setMinimumSize(new Dimension(0, 0));
        respWrapper.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqWrapper, respWrapper);
        splitPane.setResizeWeight(0.5);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(buildStepHeader(), BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
    }

    /**
     * Returns the current editor selection translated to regex-suggestion coordinates.
     *
     * <p>The response editor's selection wins when both editors have one; otherwise the request
     * editor's selection is used, so regex rules can be suggested for either side. A selection in
     * the start line or headers maps to {@code HEADERS}, one in the body to {@code BODY}, and one
     * crossing the header/body boundary to {@code FULL_MESSAGE}.
     *
     * @return selection suitable for regex suggestion
     */
    public Optional<RegexSelection> regexSelection() {
        Optional<RegexSelection> response = responseEditor.selection().flatMap(selection ->
                map(ExtractRule.SourceSide.RESPONSE, responseEditor.getResponse(), selection));
        if (response.isPresent()) {
            return response;
        }
        return requestEditor.selection().flatMap(selection ->
                map(ExtractRule.SourceSide.REQUEST, requestEditor.getRequest(), selection));
    }

    private static Optional<RegexSelection> map(ExtractRule.SourceSide side, HttpMessage message, Selection selection) {
        if (message == null || selection.contents().length() == 0) {
            return Optional.empty();
        }
        try {
            ByteArray raw = message.toByteArray();
            int bodyOffset = Math.min(message.bodyOffset(), raw.length());
            int start = selection.offsets().startIndexInclusive();
            int end = selection.offsets().endIndexExclusive();
            if (start < 0 || end > raw.length() || start >= end) {
                return Optional.empty();
            }
            // Montoya offsets are bytes; convert to characters as decoded by the same ByteArray.
            String head = bodyOffset == 0 ? "" : raw.subArray(0, bodyOffset).toString();
            int startChar = charOffset(raw, bodyOffset, head, start);
            int endChar = charOffset(raw, bodyOffset, head, end);
            return RegexSelection.fromRawMessage(side, head, message.bodyToString(), startChar, endChar);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static int charOffset(ByteArray raw, int bodyOffset, String head, int byteOffset) {
        if (byteOffset <= bodyOffset) {
            return byteOffset == 0 ? 0 : raw.subArray(0, byteOffset).toString().length();
        }
        return head.length() + raw.subArray(bodyOffset, byteOffset).toString().length();
    }

    /** Returns the response currently shown for optional extraction discovery. */
    public Optional<burp.api.montoya.http.message.responses.HttpResponse> displayedResponse() {
        return ResponseExtractionDialog.available(responseEditor.getResponse());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

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
        bar.add(Box.createHorizontalStrut(12));
        bar.add(new RequestSendModeControl(workspaceController));
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
