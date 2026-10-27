package dev.nquangit.corestra.ui.manual;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.*;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.*;
import dev.nquangit.corestra.manual.*;
import dev.nquangit.corestra.session.*;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.StableSidebarSplitPane;
import dev.nquangit.corestra.ui.flowise.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One editable editor pair for all manual drafts, with explicit step/range execution and runtime variables. */
public final class ManualTestingPanel extends JPanel implements AutoCloseable {
    private final FlowWorkspaceController controller;
    private final FlowWorkspaceController source;
    private final ManualTestRunner runner;
    private final SessionManager sessions;
    private final AccessUiController access;
    private final FlowExecutionLog log;
    private final HttpRequestEditor request;
    private final HttpResponseEditor response;
    private final JComboBox<FlowItem> drafts = new JComboBox<>();
    private final JComboBox<SessionItem> session = new JComboBox<>();
    private final JComboBox<String> suggestedVariables = new JComboBox<>();
    private final JComboBox<String> tool = new JComboBox<>(new String[]{"REPEATER", "PROXY", "SCANNER", "INTRUDER"});
    private final JTextField origins = new JTextField(18), target = new JTextField(30), name = new JTextField(18);
    private final JTextField status = new JTextField("Copy a Flowise flow or create a manual flow. Requests are sent only by Run buttons.");
    private final DefaultTableModel variables = new DefaultTableModel(new String[]{"Variable", "Value"}, 0);
    private final JTable variableTable = new JTable(variables);
    private final Map<String, ManualTestRunner.State> states = new HashMap<>();
    private final Map<String, ManualRunProgress> progressByFlow = new HashMap<>();
    private final JLabel progressLabel = new JLabel("Run progress");
    private final Map<String, Long> resetAt = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "corestra-manual-test"); t.setDaemon(true); return t; });
    private final AtomicReference<Thread> activeThread = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<JButton> idleButtons = new ArrayList<>();
    private final JButton stop = StyledButton.danger("Stop");
    private volatile boolean running, closed;
    private boolean refreshing;
    private String displayedFlow, displayedStep;
    private StoredRequestTemplate displayedTemplate;
    private final StepListPanel steps;
    private final JLayer<JPanel> stepLayer;
    private final JCheckBox keepVariables = new JCheckBox("Keep variables when changing session");
    private final JCheckBox applyInjectRules = new JCheckBox("Apply inject rules for this request", true);

    /** Creates the dedicated manual workspace from shared runtime services. */
    public ManualTestingPanel(FlowWorkspaceController controller, FlowWorkspaceController source, ManualTestRunner runner,
                              SessionManager sessions, AccessUiController access, FlowExecutionLog log, MontoyaApi api) {
        super(new BorderLayout(4, 4));
        this.controller = Objects.requireNonNull(controller); this.source = Objects.requireNonNull(source);
        this.runner = Objects.requireNonNull(runner); this.sessions = Objects.requireNonNull(sessions); this.access = Objects.requireNonNull(access); this.log = Objects.requireNonNull(log);
        request = api.userInterface().createHttpRequestEditor(); response = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        steps = new StepListPanel(controller);
        stepLayer = new JLayer<>(steps);
        JPanel shield = new JPanel(); shield.setOpaque(false);
        shield.addMouseListener(new java.awt.event.MouseAdapter() {});
        shield.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {});
        stepLayer.setGlassPane(shield); shield.setVisible(false);
        add(toolbar(), BorderLayout.NORTH);
        JPanel left = new JPanel(new BorderLayout()); left.add(stepLayer, BorderLayout.CENTER);
        progressLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        progressLabel.setToolTipText("Latest run: green = done, yellow = running/preparing, red = failed, gray = not run/skipped/stopped. HTTP status is shown in row tooltips.");
        left.add(progressLabel, BorderLayout.NORTH);
        JPanel stepActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        stepActions.add(button("+ Request", this::addRequest)); stepActions.add(button("Duplicate", this::duplicateRequest)); stepActions.add(button("Remove", this::removeRequest)); left.add(stepActions, BorderLayout.SOUTH);
        JPanel editors = new JPanel(new BorderLayout());
        JPanel address = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        address.add(new JLabel("Step")); address.add(name); address.add(new JLabel("Target origin")); address.add(target);
        address.add(new RequestSendModeControl(controller));
        var saveRequest = button("Save request", () -> { if (commitDisplayed(true)) status("Request saved to the manual flow. No request was sent."); });
        saveRequest.setToolTipText("Save edits without sending. Switching requests or pressing Run also saves automatically.");
        address.add(saveRequest);
        target.setToolTipText("Actual routing destination, independent of Host. Exact scheme://host:port; no placeholders.");
        editors.add(address, BorderLayout.NORTH);
        JPanel requestView = new JPanel(new BorderLayout()); requestView.add(request.uiComponent(), BorderLayout.CENTER);
        JPanel responseView = new JPanel(new BorderLayout()); responseView.add(response.uiComponent(), BorderLayout.CENTER);
        for (JPanel view : List.of(requestView, responseView)) { view.setMinimumSize(new Dimension(0, 0)); view.setPreferredSize(new Dimension(100, 100)); }
        JSplitPane messages = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, requestView, responseView); messages.setResizeWeight(.5);
        editors.add(messages, BorderLayout.CENTER);
        JPanel hints = new JPanel(new GridLayout(0, 1));
        hints.add(new JLabel("Inline: {{token}}, Bearer {{auth.accessToken}}, {{id|urlEncode}}. Replace a placeholder with literal test data to override it."));
        hints.add(new JLabel("Edits save automatically when switching requests/flows or pressing Run. Save request stores edits without sending."));
        editors.add(hints, BorderLayout.SOUTH);
        JTabbedPane details = new JTabbedPane();
        details.addTab("Variables", variablePanel());
        JPanel rules = new JPanel(new BorderLayout());
        JPanel ruleControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        applyInjectRules.setName("manualApplyInjectRules");
        applyInjectRules.setToolTipText("Temporarily skip retained inject rules without changing their Enabled flags. Inline templates, extractors and session injection are separate.");
        applyInjectRules.addActionListener(e -> {
            try { toggleInjectRules(); }
            catch (RuntimeException invalid) { refreshInjectionGate(); error(invalid); }
        });
        ruleControls.add(applyInjectRules);
        ruleControls.add(new JLabel("Enabled rules run after the editable template when checked."));
        rules.add(ruleControls, BorderLayout.NORTH);
        rules.add(new RuleSummaryPanel(controller, Optional::empty, access, () -> ResponseExtractionDialog.available(response.getResponse())), BorderLayout.CENTER);
        details.addTab("Extract / Inject Rules", rules);
        details.addTab("Conditions & Actions", new ConditionActionPanel(controller));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editors, details); right.setResizeWeight(.78); right.setDividerLocation(440); right.setOneTouchExpandable(true);
        JSplitPane split = new StableSidebarSplitPane(left,
                new FlowEditorLogPanel(right, new FlowLogPanel(controller, log, api)), 270);
        split.setName("manualRequestListSplit");
        add(split, BorderLayout.CENTER); status.setEditable(false); add(status, BorderLayout.SOUTH);
        drafts.addActionListener(e -> {
            if (!refreshing && !running && drafts.getSelectedItem() instanceof FlowItem item) {
                controller.flows().stream().filter(f -> f.id().equals(item.id())).findFirst().ifPresent(controller::setActiveFlow);
            }
        });
        session.addActionListener(e -> optionsChanged()); tool.addActionListener(e -> optionsChanged());
        origins.addActionListener(e -> optionsChanged());
        origins.setToolTipText("Additional exact origins allowed for a pinned session, comma-separated. Empty = first request origin only.");
        stop.setEnabled(false); stop.addActionListener(e -> cancel());
        controller.addFlowChangeListener(this::refresh);
        reloadSessions(); refresh();
    }

    private JPanel toolbar() {
        JPanel all = new JPanel(); all.setLayout(new BoxLayout(all, BoxLayout.Y_AXIS));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        drafts.setPreferredSize(new Dimension(245, 26));
        row.add(new JLabel("Manual flow")); row.add(drafts); row.add(button("Copy from Flowise…", this::chooseSource));
        drafts.setToolTipText("Independent editable flow, saved in the Burp project. Runtime variables are kept only in memory.");
        row.add(button("+ New flow", this::newDraft)); row.add(button("Rename", this::renameDraft)); row.add(button("Delete", this::deleteDraft)); all.add(row);
        JPanel run = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        run.add(button("Run selected", () -> run(0))); run.add(button("Run from here", () -> run(1))); run.add(button("Run all", () -> run(2)));
        run.add(stop); run.add(button("Reset runtime", this::resetRuntime)); run.add(new JLabel("Session")); run.add(session);
        run.add(button("Reload", this::reloadSessions)); run.add(new JLabel("Rule tool")); run.add(tool); all.add(run);
        JPanel permissions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        permissions.add(new JLabel("Extra session origins")); permissions.add(origins);
        permissions.add(keepVariables);
        permissions.add(new JLabel("Session injection runs last. Choose ‘No session injection’ when testing auth fields manually.")); all.add(permissions);
        return all;
    }

    private JPanel variablePanel() {
        JPanel panel = new JPanel(new BorderLayout()); panel.add(new JScrollPane(variableTable), BorderLayout.CENTER);
        variableTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN); variableTable.getColumnModel().getColumn(0).setPreferredWidth(190);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(button("+ Variable", () -> variables.addRow(new String[]{"", ""})));
        buttons.add(button("Remove", () -> { int row = variableTable.getSelectedRow(); if (row >= 0) variables.removeRow(variableTable.convertRowIndexToModel(row)); }));
        buttons.add(button("Load Auth / Data…", this::loadProfiles));
        suggestedVariables.setPreferredSize(new Dimension(210, 26));
        buttons.add(new JLabel("Available")); buttons.add(suggestedVariables);
        buttons.add(button("Copy placeholder", () -> {
            int row = variableTable.getSelectedRow();
            String key = row < 0 ? Objects.toString(suggestedVariables.getSelectedItem(), "")
                    : Objects.toString(variables.getValueAt(variableTable.convertRowIndexToModel(row), 0), "").strip();
            if (!key.isEmpty()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection("{{" + key + "}}"), null);
        }));
        variables.addTableModelListener(e -> updateSuggestions());
        panel.add(buttons, BorderLayout.SOUTH); return panel;
    }

    private JButton button(String label, Runnable action) {
        JButton button = StyledButton.neutral(label); idleButtons.add(button);
        button.addActionListener(e -> { if (running || closed) return; try { action.run(); } catch (RuntimeException ex) { error(ex); } }); return button;
    }

    /** Copies a Flowise flow without altering its original definitions, state or native editors. */
    public void copyFrom(FlowDefinition flow) {
        assertIdle(); if (!commitDisplayed(true)) return;
        var copy = new ManualFlowImporter().copy(flow, source.flows());
        List<FlowDefinition> combined = new ArrayList<>(controller.flows()); combined.addAll(copy.flows());
        controller.replaceAll(combined); controller.setActiveFlow(copy.flows().getFirst());
        status("Copied " + copy.flows().size() + " manual flow(s); " + copy.notices().size() + " injection rule(s) retained in Rules.");
        if (!copy.notices().isEmpty()) {
            JTextArea text = new JTextArea(String.join("\n", copy.notices()), 10, 70); text.setEditable(false); text.setCaretPosition(0);
            JOptionPane.showMessageDialog(this, new JScrollPane(text), "Manual copy: retained injection rules", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void chooseSource() {
        FlowItem[] items = source.flows().stream().map(f -> new FlowItem(f.id(), f.name())).toArray(FlowItem[]::new);
        if (items.length == 0) throw new IllegalArgumentException("No Flowise flow to copy");
        var item = (FlowItem)JOptionPane.showInputDialog(this, "Choose a source flow", "Copy to Manual Testing", JOptionPane.PLAIN_MESSAGE, null, items, items[0]);
        if (item != null) source.flows().stream().filter(f -> f.id().equals(item.id())).findFirst().ifPresent(this::copyFrom);
    }
    private void newDraft() {
        if (!commitDisplayed(true)) return;
        String label = JOptionPane.showInputDialog(this, "Manual flow name", "Manual test"); if (label == null || label.isBlank()) return;
        var flow = new FlowDefinition(UUID.randomUUID().toString(), label, "", true, List.of(), null, null, null);
        controller.addFlow(flow); controller.setActiveFlow(flow); addRequest();
    }
    private void renameDraft() {
        var f = controller.activeFlow(); if (f == null || !commitDisplayed(true)) return;
        f = controller.activeFlow();
        String label = JOptionPane.showInputDialog(this, "Manual flow name", f.name()); if (label == null || label.isBlank()) return;
        controller.updateFlow(new FlowDefinition(f.id(), label, f.description(), f.enabled(), f.steps(), f.rules(), f.aggregation(), f.triggerConfig()));
    }
    private void deleteDraft() {
        var f = controller.activeFlow(); if (f == null) return;
        if (JOptionPane.showConfirmDialog(this, "Delete manual flow ‘" + f.name() + "’?", "Delete manual flow", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            displayedFlow = null; displayedStep = null; controller.removeFlow(f);
        }
    }
    private void addRequest() {
        var f = controller.activeFlow(); if (f == null || !commitDisplayed(true)) return; f = controller.activeFlow();
        var t = new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), "");
        var s = new FlowStep(UUID.randomUUID().toString(), "Request " + (f.steps().size() + 1), true, "", t, List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        List<FlowStep> list = new ArrayList<>(f.steps()); list.add(s); updateSteps(f, list); controller.setSelectedStep(s);
    }
    private void duplicateRequest() {
        var f = controller.activeFlow(); var s = controller.selectedStep(); if (f == null || s == null || !commitDisplayed(true)) return;
        f = controller.activeFlow(); s = controller.selectedStep();
        var clone = new FlowStep(UUID.randomUUID().toString(), s.name() + " (copy)", s.enabled(), s.notes(), s.requestTemplate(), s.extractRules(), s.injectRules(),
                s.preconditions(), s.errorStrategy(), null, StepTriggerRule.disabled(), s.includeInAggregation(), s.conditionActionRules(), s.sendModeOverride(), s.manualInjectRulesEnabled());
        List<FlowStep> list = new ArrayList<>(f.steps()); list.add(list.indexOf(s) + 1, clone); updateSteps(f, list); controller.setSelectedStep(clone);
    }
    private void removeRequest() {
        var f = controller.activeFlow(); var s = controller.selectedStep(); if (f == null || s == null) return;
        List<FlowStep> list = f.steps().stream().filter(step -> !step.id().equals(s.id())).toList();
        // Reject deleting a referenced prerequisite rather than silently changing its meaning.
        updateSteps(f, list); if (!list.isEmpty()) controller.setSelectedStep(list.getFirst());
    }
    private void updateSteps(FlowDefinition f, List<FlowStep> list) {
        controller.updateFlow(new FlowDefinition(f.id(), f.name(), f.description(), f.enabled(), list, f.rules(), f.aggregation(), f.triggerConfig()));
    }

    private boolean commitDisplayed(boolean show) {
        if (displayedFlow == null || displayedStep == null) return true;
        var flow = controller.flows().stream().filter(f -> f.id().equals(displayedFlow)).findFirst().orElse(null);
        if (flow == null) return true;
        var step = flow.steps().stream().filter(s -> s.id().equals(displayedStep)).findFirst().orElse(null); if (step == null) return true;
        try {
            if (!request.isModified() && Objects.equals(target.getText(), ManualRequestCodec.origin(step.requestTemplate())) && name.getText().equals(step.name())) return true;
            var edited = request.getRequest(); if (edited == null) throw new IllegalArgumentException("No valid request in the editor");
            var template = ManualRequestCodec.parse(target.getText(), edited.toString());
            if (name.getText().isBlank()) throw new IllegalArgumentException("Step name must not be empty");
            var updated = step.withRequestTemplate(template).withName(name.getText());
            if (!updated.equals(step)) updateSteps(flow, flow.steps().stream().map(s -> s.id().equals(updated.id()) ? updated : s).toList());
            displayedTemplate = template; return true;
        } catch (RuntimeException e) { status("Template not saved: " + e.getMessage()); if (show) error(e); return false; }
    }

    private Map<String, String> editedVariables() {
        if (variableTable.isEditing() && !variableTable.getCellEditor().stopCellEditing()) throw new IllegalArgumentException("Finish editing the variable cell");
        Map<String, String> values = new LinkedHashMap<>();
        for (int row = 0; row < variables.getRowCount(); row++) {
            String key = Objects.toString(variables.getValueAt(row, 0), "").strip(), value = Objects.toString(variables.getValueAt(row, 1), "");
            if (key.isBlank() && value.isEmpty()) continue;
            if (key.isBlank() || key.chars().anyMatch(c -> Character.isWhitespace(c) || c == '|' || c == '{' || c == '}')) throw new IllegalArgumentException("Invalid variable key at row " + (row + 1));
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate variable: " + key);
        }
        return Map.copyOf(values);
    }
    private ManualRunOptions options() {
        var item = (SessionItem)session.getSelectedItem(); if (item == null) throw new IllegalArgumentException("Choose a session mode");
        return new ManualRunOptions(item.mode(), item.id(), (String)tool.getSelectedItem(), Arrays.stream(origins.getText().split(",")).map(String::strip).filter(s -> !s.isBlank()).toList());
    }
    private void run(int mode) {
        if (!commitDisplayed(true)) return;
        var flow = controller.activeFlow(); if (flow == null || flow.steps().isEmpty()) throw new IllegalArgumentException("Add a request first");
        var selected = controller.selectedStep(); int index = selected == null ? -1 : flow.steps().indexOf(selected);
        if (mode != 2 && index < 0) throw new IllegalArgumentException("Select a request");
        int first = mode == 2 ? 0 : index, last = mode == 0 ? index : flow.steps().size() - 1;
        var input = editedVariables(); var config = options(); var previous = states.getOrDefault(flow.id(), ManualTestRunner.State.empty());
        if (states.containsKey(flow.id()) && !previous.options().equals(config) && !keepVariables.isSelected())
            throw new IllegalArgumentException("Session options changed. Reset runtime first, or explicitly enable Keep variables when changing session.");
        var progress = new ManualRunProgress(flow, first, last, mode == 0);
        progressByFlow.put(flow.id(), progress);
        cancelled.set(false); setRunning(true); showProgress(); status("Running ‘" + flow.name() + "’…");
        executor.submit(() -> {
            activeThread.set(Thread.currentThread());
            try {
                var outcome = runner.run(flow, first, last, mode == 0, input, previous, config, cancelled, event ->
                    SwingUtilities.invokeLater(() -> {
                        if (closed || !running || progressByFlow.get(flow.id()) != progress) return;
                        progress.accept(event, cancelled.get()); showProgress();
                        if (event.phase() == StepExecutionProgress.Phase.STARTED && !cancelled.get())
                            status("Running ‘" + event.flowName() + " / " + event.stepName() + "’"
                                    + (event.attempt() > 1 ? " — attempt " + event.attempt() : "") + "…");
                    }));
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    progress.finish(cancelled.get()); showProgress();
                    states.put(flow.id(), outcome.state());
                    if (controller.activeFlow() != null && controller.activeFlow().id().equals(flow.id())) {
                        showVariables(outcome.state().variables()); showResponse();

                    }
                    boolean failedSteps = outcome.result().stepRecords().stream().anyMatch(r -> !r.success());
                    status(cancelled.get() ? "Stopped. Review the log; a request already sent cannot be recalled."
                            : outcome.result().success() ? "Completed" + (failedSteps ? " with failed steps" : "") + " — " + outcome.result().stepRecords().size() + " request(s); inspect responses in Flow Log"
                            : "FAILED — " + outcome.result().failureMessage());
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> { if (!closed) { progress.finish(cancelled.get()); showProgress(); status("Stopped: " + e.getMessage()); } }); }
            finally { activeThread.set(null); Thread.interrupted(); SwingUtilities.invokeLater(() -> { if (!closed) setRunning(false); }); }
        });
    }

    private void refresh() {
        if (refreshing || closed) return; refreshing = true;
        try {
            var active = controller.activeFlow(); var selected = controller.selectedStep();
            String nextFlow = active == null ? null : active.id(), nextStep = selected == null ? null : selected.id();
            boolean changed = !Objects.equals(displayedFlow, nextFlow) || !Objects.equals(displayedStep, nextStep);
            if (changed && displayedFlow != null) {
                if (!commitDisplayed(false)) {
                    var old = controller.flows().stream().filter(f -> f.id().equals(displayedFlow)).findFirst().orElse(null);
                    if (old != null) { controller.setActiveFlow(old); old.steps().stream().filter(s -> s.id().equals(displayedStep)).findFirst().ifPresent(controller::setSelectedStep); return; }
                }
                if (!Objects.equals(displayedFlow, nextFlow) && states.containsKey(displayedFlow)) {
                    var state = states.get(displayedFlow); states.put(displayedFlow, new ManualTestRunner.State(editedVariables(), state.history(), state.binding(), state.options()));
                } else if (!Objects.equals(displayedFlow, nextFlow)) states.put(displayedFlow, new ManualTestRunner.State(editedVariables(), Map.of(), Map.of(), options()));
            }
            active = controller.activeFlow(); selected = controller.selectedStep();
            if (!Objects.equals(displayedFlow, nextFlow)) {
                var state = states.getOrDefault(nextFlow, ManualTestRunner.State.empty());
                showVariables(state.variables()); restoreOptions(state.options());
            }
            displayedFlow = nextFlow; displayedStep = nextStep;
            if (selected == null) { request.setRequest(HttpRequest.httpRequest("")); response.setResponse(HttpResponse.httpResponse("")); displayedTemplate = null; name.setText(""); target.setText(""); }
            else if (changed || !Objects.equals(displayedTemplate, selected.requestTemplate())) {
                request.setRequest(HttpRequest.httpRequest(ManualRequestCodec.format(selected.requestTemplate())));
                displayedTemplate = selected.requestTemplate(); name.setText(selected.name()); target.setText(ManualRequestCodec.origin(displayedTemplate));
            } else if (!name.getText().equals(selected.name())) name.setText(selected.name());
            refreshInjectionGate();
            drafts.removeAllItems(); for (var f : controller.flows()) { var item = new FlowItem(f.id(), f.name()); drafts.addItem(item); if (f.id().equals(nextFlow)) drafts.setSelectedItem(item); }
            var ids = controller.flows().stream().map(FlowDefinition::id).toList(); states.keySet().retainAll(ids); resetAt.keySet().retainAll(ids); progressByFlow.keySet().retainAll(ids);
            showProgress();
            showResponse(); updateSuggestions();
        } catch (RuntimeException e) {
            status("Could not switch request: " + e.getMessage());
            var old = controller.flows().stream().filter(f -> f.id().equals(displayedFlow)).findFirst().orElse(null);
            if (old != null) { controller.setActiveFlow(old); old.steps().stream().filter(s -> s.id().equals(displayedStep)).findFirst().ifPresent(controller::setSelectedStep); }
        }
        finally { refreshing = false; }
    }
    private void updateSuggestions() {
        var keys = new TreeSet<String>();
        for (int i = 0; i < variables.getRowCount(); i++) {
            String key = Objects.toString(variables.getValueAt(i, 0), "").strip(); if (!key.isBlank()) keys.add(key);
        }
        var flow = controller.activeFlow(); var selected = controller.selectedStep();
        if (flow != null && selected != null) {
            for (var step : flow.steps()) {
                if (step.id().equals(selected.id())) break;
                if (step.enabled()) step.extractRules().stream().filter(r -> r.enabled()).forEach(r -> keys.add(r.targetVariableName()));
            }
        }
        String old = Objects.toString(suggestedVariables.getSelectedItem(), "");
        suggestedVariables.removeAllItems(); keys.forEach(suggestedVariables::addItem); if (keys.contains(old)) suggestedVariables.setSelectedItem(old);
    }

    private void toggleInjectRules() {
        if (refreshing || closed) return;
        if (running) { refreshInjectionGate(); return; }
        boolean apply = applyInjectRules.isSelected();
        if (!commitDisplayed(true)) { refreshInjectionGate(); return; }
        var flow = controller.activeFlow(); var step = controller.selectedStep();
        if (flow == null || step == null || !Objects.equals(displayedFlow, flow.id())
                || !Objects.equals(displayedStep, step.id()) || !flow.hasStep(step.id())) {
            refreshInjectionGate(); return;
        }
        var updated = step.withManualInjectRulesEnabled(apply);
        updateSteps(flow, flow.steps().stream().map(s -> s.id().equals(step.id()) ? updated : s).toList());
        status(apply ? "Inject rules restored for this manual request; only individually enabled rules will run."
                : "Inject rules paused for this manual request; individual Enabled flags are preserved. Inline templates, extractors and session injection remain active.");
    }

    private void refreshInjectionGate() {
        var flow = controller.activeFlow(); var step = controller.selectedStep();
        boolean valid = flow != null && step != null && flow.hasStep(step.id());
        applyInjectRules.setSelected(!valid || step.manualInjectRulesEnabled());
        applyInjectRules.setEnabled(valid && !running && !closed);
    }

    private void showVariables(Map<String, String> values) { variables.setRowCount(0); values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> variables.addRow(new String[]{e.getKey(), e.getValue()})); }
    private void showResponse() {
        var entry = log.snapshot().stream().filter(e -> Objects.equals(e.flowId(), displayedFlow) && Objects.equals(e.stepId(), displayedStep)
                && e.completedAtEpochMillis() >= resetAt.getOrDefault(displayedFlow, 0L)).findFirst();
        response.setResponse(HttpResponse.httpResponse(entry.map(FlowExecutionLog.Entry::response).orElse("")));
        if (response.uiComponent() instanceof JComponent component)
            component.setToolTipText(entry.map(e -> e.success() ? "Last response" : e.errorMessage()).orElse("No retained response for this step"));
    }
    private void reloadSessions() {
        var previous = (SessionItem)session.getSelectedItem(); boolean was = refreshing; refreshing = true;
        session.removeAllItems(); session.addItem(new SessionItem(ManualRunOptions.Mode.SCOPED, "", "Session Handler scope / selection"));
        session.addItem(new SessionItem(ManualRunOptions.Mode.ASK, "", "Ask once for this runtime")); session.addItem(new SessionItem(ManualRunOptions.Mode.NONE, "", "No session injection"));
        sessions.profiles().stream().filter(SessionProfile::enabled).forEach(p -> session.addItem(new SessionItem(ManualRunOptions.Mode.FIXED, p.id(), "Use: " + p.name())));
        if (previous != null) {
            boolean restored = false;
            for (int i = 0; i < session.getItemCount(); i++) if (session.getItemAt(i).id().equals(previous.id()) && session.getItemAt(i).mode() == previous.mode()) { session.setSelectedIndex(i); restored = true; break; }
            // Keep a disabled/deleted selection explicit; never silently fall back to scope.
            if (!restored) { session.addItem(new SessionItem(previous.mode(), previous.id(), previous.label() + " (unavailable)")); session.setSelectedIndex(session.getItemCount() - 1); }
        }
        refreshing = was;
    }
    private void restoreOptions(ManualRunOptions options) {
        int chosen = -1;
        for (int i = 0; i < session.getItemCount(); i++) if (session.getItemAt(i).mode() == options.mode() && session.getItemAt(i).id().equals(options.profileId())) { chosen = i; break; }
        if (chosen < 0) { session.addItem(new SessionItem(options.mode(), options.profileId(), "Unavailable: " + options.profileId())); chosen = session.getItemCount() - 1; }
        session.setSelectedIndex(chosen); tool.setSelectedItem(options.tool()); origins.setText(String.join(", ", options.additionalOrigins()));
    }
    private void optionsChanged() {
        if (refreshing || running || displayedFlow == null) return;
        Map<String, String> retained = keepVariables.isSelected() ? editedVariables() : Map.of();
        resetRuntime(); showVariables(retained);
        if (!retained.isEmpty()) status("Session binding cleared; variables deliberately retained. Review actor-specific values before sending.");
    }
    private void resetRuntime() {
        if (displayedFlow == null) return;
        progressByFlow.remove(displayedFlow); showProgress();
        states.remove(displayedFlow); resetAt.put(displayedFlow, System.currentTimeMillis()); showVariables(Map.of()); showResponse();
        status("Runtime cleared. Templates and rules are preserved; the next run obtains a fresh session binding.");
    }
    private void loadProfiles() {
        var auths = new JComboBox<AuthChoice>(); auths.addItem(new AuthChoice(null)); access.authProfiles().stream().filter(AuthProfile::enabled).forEach(a -> auths.addItem(new AuthChoice(a)));
        var datas = new JComboBox<DataChoice>(); datas.addItem(new DataChoice(null)); access.dataProfiles().stream().filter(DataProfile::enabled).forEach(d -> datas.addItem(new DataChoice(d)));
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6)); form.add(new JLabel("Auth Profile")); form.add(auths); form.add(new JLabel("Data Profile")); form.add(datas);
        if (JOptionPane.showConfirmDialog(this, form, "Load runtime variables", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        AuthProfile auth = ((AuthChoice)auths.getSelectedItem()).profile(); DataProfile data = ((DataChoice)datas.getSelectedItem()).profile();
        if (auth != null) {
            String id = auth.id(); auth = access.authProfiles().stream().filter(p -> p.id().equals(id) && p.enabled()).findFirst().orElseThrow(() -> new IllegalArgumentException("Selected Auth Profile is missing or disabled"));
        }
        if (data != null) {
            String id = data.id(); data = access.dataProfiles().stream().filter(p -> p.id().equals(id) && p.enabled()).findFirst().orElseThrow(() -> new IllegalArgumentException("Selected Data Profile is missing or disabled"));
        }
        if (data != null && !data.ownerAuthProfileId().isBlank() && (auth == null || !data.ownerAuthProfileId().equals(auth.id()))) throw new IllegalArgumentException("Data Profile belongs to a different Auth Profile");
        Map<String, String> values = new LinkedHashMap<>(editedVariables()); values.putAll(FlowRunContext.variables(auth, data, Map.of())); showVariables(values);
    }
    private void setRunning(boolean value) {
        running = value; idleButtons.forEach(b -> b.setEnabled(!value)); drafts.setEnabled(!value); session.setEnabled(!value); tool.setEnabled(!value); origins.setEnabled(!value);
        name.setEnabled(!value); target.setEnabled(!value); variableTable.setEnabled(!value); stepLayer.getGlassPane().setVisible(value); keepVariables.setEnabled(!value); stop.setEnabled(value);
        refreshInjectionGate();
        showProgress();
        // Editing the native template is allowed while a run uses its immutable snapshot; it is saved before the next run.
    }
    private void cancel() { cancelled.set(true); Thread thread = activeThread.get(); if (thread != null) thread.interrupt(); status("Stopping… waiting for the current send to return; no further manual request will be sent."); }
    /** Rejects project cleanup/import while a request may still be in flight. */
    public void assertIdle() { if (running) throw new IllegalStateException("Stop the active Manual Testing run and wait for it to finish first"); if (closed) throw new IllegalStateException("Manual Testing is closed"); }
    /** Clears runtime and draft configuration during explicit project cleanup. */
    public void clearWorkspace() { assertIdle(); displayedFlow = null; displayedStep = null; states.clear(); resetAt.clear(); progressByFlow.clear(); log.clear(); controller.replaceAll(List.of()); controller.setActiveFlow(null); showVariables(Map.of()); showProgress(); }
    /** Drops all actor-specific continuation state after importing/replacing project configuration. */
    public void resetAllRuntime() {
        assertIdle(); states.clear(); resetAt.clear(); progressByFlow.clear(); log.clear(); showProgress();
        boolean was = refreshing; refreshing = true;
        try { reloadSessions(); restoreOptions(ManualRunOptions.defaults()); } finally { refreshing = was; }
        showVariables(Map.of()); showResponse();
    }
    private void status(String text) { status.setText(text); status.setCaretPosition(0); status.setToolTipText(text); }
    private void error(RuntimeException e) { status(e.getMessage()); JOptionPane.showMessageDialog(this, e.getMessage(), "Manual Testing", JOptionPane.ERROR_MESSAGE); }
    @Override public void close() {
        closed = true; cancelled.set(true); Thread active = activeThread.get(); if (active != null) active.interrupt(); executor.shutdownNow();
        Runnable cleanup = () -> { states.clear(); progressByFlow.clear(); log.clear(); refreshInjectionGate(); status("Manual Testing closed"); };
        if (SwingUtilities.isEventDispatchThread()) cleanup.run(); else SwingUtilities.invokeLater(cleanup);
    }
    private void showProgress() {
        var flow = controller.activeFlow(); var progress = flow == null ? null : progressByFlow.get(flow.id());
        steps.setExecutionStatuses(flow == null ? null : flow.id(), progress == null ? Map.of() : progress.snapshot());
        progressLabel.setText(progress == null ? "Run progress · not run" : (running ? "Running · " : "Last run · ") + progress.summary());
    }
    private record FlowItem(String id, String label) { @Override public String toString() { return label; } }
    private record SessionItem(ManualRunOptions.Mode mode, String id, String label) { @Override public String toString() { return label; } }
    private record AuthChoice(AuthProfile profile) { @Override public String toString() { return profile == null ? "None" : profile.name(); } }
    private record DataChoice(DataProfile profile) { @Override public String toString() { return profile == null ? "None" : profile.name(); } }
}
