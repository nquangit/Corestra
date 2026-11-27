package dev.nquangit.corestra.ui.watch;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.ui.component.StableSidebarSplitPane;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.UiTheme;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchDefinitionValidator;
import dev.nquangit.corestra.watch.WatchDisplayOptions;
import dev.nquangit.corestra.watch.WatchExpectation;
import dev.nquangit.corestra.watch.WatchObservation;
import dev.nquangit.corestra.watch.WatchRepository;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;
import dev.nquangit.corestra.watch.WatchTiming;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * "Watch" extension tab: configure Repeater-only before/after state watches and review recent results.
 *
 * <p>Left: watch list with enable checkboxes. Right: tabbed editor (General, Probe, Request
 * variables, Observations, Expectations, Display &amp; timing) with Save/Revert. Bottom: recent results
 * fed by the live handler through {@link #showResult(WatchResult)}.</p>
 *
 * <p>All record rebuilding lives in {@link WatchEditorModel}; validation in
 * {@link WatchDefinitionValidator}. Probe tests run off the EDT.</p>
 */
public final class WatchPanel extends JPanel implements AutoCloseable {
    private static final String NOTE_SCOPE = "Applies to Repeater only. Methods empty = any; host/path regexes use find(); blank = any.";

    private final WatchRepository repository;
    private final Function<WatchDefinition, WatchSnapshot> probeTester;
    private final Runnable repositoryListener = () -> SwingUtilities.invokeLater(this::reloadList);
    private Predicate<String> confirm = message -> JOptionPane.showConfirmDialog(this, message, "Watch",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;

    // List
    private final WatchListModel listModel = new WatchListModel();
    private final JTable watchTable = new JTable(listModel);
    private boolean adjustingSelection;

    // Editor
    private WatchDefinition current;
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final JTextField nameField = new JTextField(24);
    private final JCheckBox enabledBox = new JCheckBox("Enabled");
    private final JTextField methodsField = new JTextField(16);
    private final JTextField hostPatternField = new JTextField(24);
    private final JTextField pathPatternField = new JTextField(24);
    private final JTextField probeHostField = new JTextField(18);
    private final JTextField probePortField = new JTextField("443", 5);
    private final JCheckBox probeHttpsBox = new JCheckBox("HTTPS", true);
    private final JTextField inheritHeadersField = new JTextField(20);
    private final ProbeEditor probeEditor;
    private final JButton testProbeButton = StyledButton.primary("Test probe");
    private final DefaultTableModel snapshotModel = readOnlyModel("Key", "Raw value", "Parsed number");
    private final RecordTableModel<WatchEditorModel.VariableDraft> variablesModel =
            new RecordTableModel<>(WatchEditorModel.VARIABLE_COLUMNS);
    private final RecordTableModel<WatchObservation> observationsModel =
            new RecordTableModel<>(WatchEditorModel.OBSERVATION_COLUMNS);
    private final RecordTableModel<WatchExpectation> expectationsModel =
            new RecordTableModel<>(WatchEditorModel.EXPECTATION_COLUMNS);
    private final JTable variablesTable = new JTable(variablesModel);
    private final JTable observationsTable = new JTable(observationsModel);
    private final JTable expectationsTable = new JTable(expectationsModel);
    private final JCheckBox headersBox = new JCheckBox("Response headers (X-Corestra-Watch*)");
    private final JCheckBox envelopeBox = new JCheckBox("JSON envelope {\"corestraWatch\":…,\"response\":…}");
    private final JCheckBox textBlockBox = new JCheckBox("Text block prepended to the body");
    private final JCheckBox annotationsBox = new JCheckBox("Burp notes + highlight");
    private final JSpinner probeTimeout = spinner(5_000, 500, 60_000, 100);
    private final JSpinner settleInterval = spinner(700, 100, 30_000, 100);
    private final JSpinner stableCount = spinner(2, 1, 10, 1);
    private final JSpinner settleMax = spinner(6_000, 0, 300_000, 500);
    private final JSpinner flowWait = spinner(60_000, 1_000, 600_000, 1_000);
    private final JTextArea messages = new JTextArea(3, 40);
    private SwingWorker<WatchSnapshot, Void> probeWorker;

    // Results
    private final WatchResultHistory history = new WatchResultHistory(WatchResultHistory.DEFAULT_CAPACITY);
    private final ResultsModel resultsModel = new ResultsModel();
    private final JTable resultsTable = new JTable(resultsModel);
    private final DefaultTableModel detailModel = readOnlyModel("Key", "Before", "After", "Delta", "Verdict", "Explanation");
    private final JTextArea notesArea = new JTextArea(3, 30);

    /**
     * Creates the Watch tab.
     *
     * @param repository  watch storage
     * @param api         Montoya API for Burp-native editors; null uses a plain text area (tests)
     * @param probeTester sends one probe for "Test probe" (blocking; called off the EDT); null disables the button
     */
    public WatchPanel(WatchRepository repository, MontoyaApi api, Function<WatchDefinition, WatchSnapshot> probeTester) {
        super(new BorderLayout(4, 4));
        this.repository = Objects.requireNonNull(repository, "repository");
        this.probeTester = probeTester;
        this.probeEditor = api == null ? new TextProbeEditor() : new BurpProbeEditor(api.userInterface().createHttpRequestEditor());

        JSplitPane top = new StableSidebarSplitPane(listPanel(), editorPanel(), 260);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, resultsPanel());
        main.setResizeWeight(0.7);
        main.setOneTouchExpandable(true);
        add(header(), BorderLayout.NORTH);
        add(main, BorderLayout.CENTER);

        repository.addListener(repositoryListener);
        reloadList();
        if (listModel.getRowCount() > 0) {
            watchTable.setRowSelectionInterval(0, 0);
        } else {
            showDefinition(null);
        }
    }

    /**
     * Adds a result to "Recent results". Safe to call from any thread.
     *
     * @param result result from the live handler
     */
    public void showResult(WatchResult result) {
        Objects.requireNonNull(result, "result");
        long receivedAt = System.currentTimeMillis();
        Runnable add = () -> {
            WatchResultHistory.Entry selected = selectedResult();
            history.add(receivedAt, result);
            resultsModel.fireTableDataChanged();
            int index = selected == null ? 0 : history.entries().indexOf(selected);
            if (index >= 0 && index < resultsModel.getRowCount()) {
                resultsTable.setRowSelectionInterval(index, index);
            }
            showResultDetail();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            add.run();
        } else {
            SwingUtilities.invokeLater(add);
        }
    }

    /** Stops listening to repository changes and cancels a running probe test. */
    @Override
    public void close() {
        repository.removeListener(repositoryListener);
        if (probeWorker != null) {
            probeWorker.cancel(true);
        }
    }

    // ── Package-private hooks for tests ─────────────────────────────────────

    void setConfirm(Predicate<String> confirm) {
        this.confirm = Objects.requireNonNull(confirm);
    }

    WatchDefinition currentDefinition() {
        return current;
    }

    JTextField nameField() {
        return nameField;
    }

    String messageText() {
        return messages.getText();
    }

    JTable watchTable() {
        return watchTable;
    }

    JTable resultsTable() {
        return resultsTable;
    }

    DefaultTableModel detailModel() {
        return detailModel;
    }

    DefaultTableModel snapshotModel() {
        return snapshotModel;
    }

    JButton testProbeButton() {
        return testProbeButton;
    }

    RecordTableModel<WatchExpectation> expectationsModel() {
        return expectationsModel;
    }

    void setProbe(String host, int port, boolean https, String raw) {
        probeHostField.setText(host);
        probePortField.setText(Integer.toString(port));
        probeHttpsBox.setSelected(https);
        probeEditor.setText(raw);
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));
        JLabel title = new JLabel("Watch — capture state before/after Repeater requests and check expectations");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        header.add(title, BorderLayout.NORTH);
        header.add(new JLabel("Only Repeater traffic is watched. A read-only probe request captures state BEFORE the request "
                + "and AFTER it until stable; results decorate the Repeater response and appear below."), BorderLayout.SOUTH);
        return header;
    }

    private JComponent listPanel() {
        JPanel panel = new JPanel(new BorderLayout(2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Watches"));
        watchTable.setName("watchList");
        watchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        watchTable.getColumnModel().getColumn(0).setMaxWidth(40);
        watchTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !adjustingSelection) {
                selectionChanged();
            }
        });
        panel.add(new JScrollPane(watchTable), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        buttons.add(button(StyledButton.success("Add"), this::addWatch));
        buttons.add(button(StyledButton.neutral("Duplicate"), this::duplicateWatch));
        buttons.add(button(StyledButton.danger("Delete"), this::deleteWatch));
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setMinimumSize(new Dimension(180, 100));
        return panel;
    }

    private JComponent editorPanel() {
        editorTabs.addTab("General", generalTab());
        editorTabs.addTab("Probe", probeTab());
        editorTabs.addTab("Request variables", tableTab(variablesTable, variablesModel,
                () -> WatchEditorModel.newVariable(UUID.randomUUID().toString()),
                "Values read from the live Repeater request (REQUEST side), usable as {{name}} in expressions and the probe. "
                        + "JSON_PATH: $.amount | REGEX: amount=(\\d+) | HEADER/COOKIE: name."));
        editorTabs.addTab("Observations", tableTab(observationsTable, observationsModel,
                () -> WatchEditorModel.newObservation(UUID.randomUUID().toString()),
                "JSON_SELECTOR/REGEX: one key = name. JSON_COLLECTION: items path ($.accounts) + element key/value dot paths "
                        + "(accountNo, balance.available) → name[key]. REGEX_COLLECTION: regex with (?<key>…) and (?<value>…)."));
        editorTabs.addTab("Expectations", expectationsTab());
        editorTabs.addTab("Display & timing", displayTimingTab());
        configureEnumEditors();

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actions.add(button(StyledButton.success("Save"), this::save));
        actions.add(button(StyledButton.neutral("Revert"), this::revert));
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        messages.setOpaque(false);
        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.NORTH);
        south.add(messages, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(2, 2));
        panel.add(editorTabs, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent generalTab() {
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = formRow(form, row, "Name", nameField);
        row = formRow(form, row, "", enabledBox);
        row = formRow(form, row, "Methods (comma list)", methodsField);
        row = formRow(form, row, "Host regex", hostPatternField);
        row = formRow(form, row, "Path regex", pathPatternField);
        JLabel note = new JLabel(NOTE_SCOPE);
        note.setForeground(UiTheme.pick(new Color(90, 90, 90), new Color(170, 170, 170)));
        formRow(form, row, "", note);
        methodsField.setToolTipText("e.g. POST, PUT. Empty matches any method.");
        hostPatternField.setToolTipText("Java regex, find() against the request host, e.g. bank\\.example\\.com");
        pathPatternField.setToolTipText("Java regex, find() against path + query, e.g. ^/api/transfer");
        return wrapNorth(form);
    }

    private JComponent probeTab() {
        JPanel target = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        target.add(new JLabel("Host"));
        target.add(probeHostField);
        target.add(new JLabel("Port"));
        target.add(probePortField);
        target.add(probeHttpsBox);
        target.add(new JLabel("Inherit headers"));
        target.add(inheritHeadersField);
        inheritHeadersField.setToolTipText("Header names copied from the live Repeater request into the probe (default Cookie, Authorization).");
        JLabel hint = new JLabel("Read-only request (e.g. GET /accounts). {{name}} placeholders are filled from request variables at run time; "
                + "leave the editor empty to keep the probe unconfigured.");

        JPanel north = new JPanel(new BorderLayout());
        north.add(target, BorderLayout.NORTH);
        north.add(hint, BorderLayout.SOUTH);

        JPanel testPanel = new JPanel(new BorderLayout(2, 2));
        testPanel.setBorder(BorderFactory.createTitledBorder("Probe test"));
        JPanel testButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        testButtons.add(button(testProbeButton, this::testProbe));
        testProbeButton.setEnabled(probeTester != null);
        testProbeButton.setToolTipText(probeTester == null ? "Probe testing is not available"
                : "Sends the probe as currently edited (unsaved edits included) and shows the observed values.");
        testPanel.add(testButtons, BorderLayout.NORTH);
        JTable snapshotTable = new JTable(snapshotModel);
        testPanel.add(new JScrollPane(snapshotTable), BorderLayout.CENTER);

        Component editor = probeEditor.component();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, testPanel);
        split.setResizeWeight(0.6);
        JPanel panel = new JPanel(new BorderLayout(2, 2));
        panel.add(north, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JComponent expectationsTab() {
        JComponent table = tableTab(expectationsTable, expectationsModel,
                () -> WatchEditorModel.newExpectation(UUID.randomUUID().toString()), null);
        JTextArea help = new JTextArea(WatchEditorModel.EXPRESSION_HELP);
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setOpaque(false);
        help.setBorder(BorderFactory.createTitledBorder("Expression grammar"));
        JPanel panel = new JPanel(new BorderLayout(2, 2));
        panel.add(table, BorderLayout.CENTER);
        panel.add(help, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent displayTimingTab() {
        JPanel display = new JPanel();
        display.setLayout(new BoxLayout(display, BoxLayout.Y_AXIS));
        display.setBorder(BorderFactory.createTitledBorder("Show the result on the Repeater response (any combination)"));
        for (JCheckBox box : List.of(headersBox, envelopeBox, textBlockBox, annotationsBox)) {
            display.add(box);
        }
        JPanel timing = new JPanel(new GridBagLayout());
        timing.setBorder(BorderFactory.createTitledBorder("Timing"));
        int row = 0;
        row = formRow(timing, row, "Probe timeout (ms)", probeTimeout);
        row = formRow(timing, row, "Settle interval (ms)", settleInterval);
        row = formRow(timing, row, "Stable captures needed", stableCount);
        row = formRow(timing, row, "Settle max (ms, 0 = no re-capture)", settleMax);
        formRow(timing, row, "Downstream flow wait (ms)", flowWait);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(display);
        panel.add(timing);
        panel.add(Box.createVerticalGlue());
        return wrapNorth(panel);
    }

    private <T> JComponent tableTab(JTable table, RecordTableModel<T> model, java.util.function.Supplier<T> factory, String hint) {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int c = 0; c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(model.getColumnClass(c) == Boolean.class ? 45 : 130);
        }
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        buttons.add(button(StyledButton.success("Add"), () -> {
            stopEditing(table);
            model.add(factory.get());
            int last = model.getRowCount() - 1;
            table.setRowSelectionInterval(last, last);
        }));
        buttons.add(button(StyledButton.danger("Remove"), () -> {
            stopEditing(table);
            int row = table.getSelectedRow();
            if (row >= 0) model.remove(row);
        }));
        buttons.add(button(StyledButton.neutral("Up"), () -> move(table, model, -1)));
        buttons.add(button(StyledButton.neutral("Down"), () -> move(table, model, 1)));
        JPanel panel = new JPanel(new BorderLayout(2, 2));
        if (hint != null) {
            JTextArea label = new JTextArea(hint);
            label.setEditable(false);
            label.setLineWrap(true);
            label.setWrapStyleWord(true);
            label.setOpaque(false);
            panel.add(label, BorderLayout.NORTH);
        }
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent resultsPanel() {
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(80);
        resultsTable.getColumnModel().getColumn(2).setMaxWidth(100);
        resultsTable.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        resultsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showResultDetail();
        });
        JTable detailTable = new JTable(detailModel);
        notesArea.setEditable(false);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        JPanel detail = new JPanel(new BorderLayout(2, 2));
        detail.add(new JScrollPane(detailTable), BorderLayout.CENTER);
        detail.add(new JScrollPane(notesArea), BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(resultsTable), detail);
        split.setResizeWeight(0.45);
        JPanel panel = new JPanel(new BorderLayout(2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Recent results (last " + WatchResultHistory.DEFAULT_CAPACITY + ")"));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        buttons.add(button(StyledButton.neutral("Clear"), () -> {
            history.clear();
            resultsModel.fireTableDataChanged();
            showResultDetail();
        }));
        panel.add(buttons, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(400, 220));
        return panel;
    }

    private void configureEnumEditors() {
        variablesTable.setDefaultEditor(ExtractRule.ExtractorType.class, new DefaultCellEditor(
                new JComboBox<>(WatchEditorModel.VARIABLE_TYPES.toArray(ExtractRule.ExtractorType[]::new))));
        variablesTable.setDefaultEditor(ExtractRule.RegexSource.class,
                new DefaultCellEditor(new JComboBox<>(ExtractRule.RegexSource.values())));
        observationsTable.setDefaultEditor(WatchObservation.Source.class,
                new DefaultCellEditor(new JComboBox<>(WatchObservation.Source.values())));
        observationsTable.setDefaultEditor(WatchObservation.ValueType.class,
                new DefaultCellEditor(new JComboBox<>(WatchObservation.ValueType.values())));
        expectationsTable.setDefaultEditor(WatchExpectation.Type.class,
                new DefaultCellEditor(new JComboBox<>(WatchExpectation.Type.values())));
    }

    // ── List / selection ────────────────────────────────────────────────────

    private void reloadList() {
        List<WatchDefinition> watches = repository.load();
        String selectedId = current == null ? null : current.id();
        adjustingSelection = true;
        try {
            listModel.setRows(watches);
            int index = indexOf(watches, selectedId);
            if (index >= 0) {
                watchTable.setRowSelectionInterval(index, index);
            } else {
                watchTable.clearSelection();
            }
        } finally {
            adjustingSelection = false;
        }
        if (current != null) {
            int index = indexOf(watches, current.id());
            if (index < 0) {
                showDefinition(null);
            } else if (!watches.get(index).equals(current) && !isDirty()) {
                showDefinition(watches.get(index));
            } else {
                current = current.withEnabled(watches.get(index).enabled());
            }
        }
        List<String> diagnostics = repository.loadDiagnostics();
        if (!diagnostics.isEmpty() && messages.getText().isBlank()) {
            showMessage(String.join("\n", diagnostics), true);
        }
    }

    private void selectionChanged() {
        int row = watchTable.getSelectedRow();
        WatchDefinition selected = row < 0 ? null : listModel.rows.get(row);
        if (selected != null && current != null && selected.id().equals(current.id())) {
            return;
        }
        if (isDirty() && !confirm.test("Discard unsaved changes to \"" + current.name() + "\"?")) {
            adjustingSelection = true;
            try {
                int index = indexOf(listModel.rows, current.id());
                if (index >= 0) watchTable.setRowSelectionInterval(index, index);
            } finally {
                adjustingSelection = false;
            }
            return;
        }
        showDefinition(selected);
    }

    private void addWatch() {
        if (isDirty() && !confirm.test("Discard unsaved changes to \"" + current.name() + "\"?")) return;
        WatchDefinition created = WatchDefinition.empty(UUID.randomUUID().toString(), "Watch " + (listModel.getRowCount() + 1));
        current = null;
        repository.upsert(created);
        selectById(created.id());
    }

    private void duplicateWatch() {
        if (current == null) return;
        WatchDefinition stored = repository.find(current.id()).orElse(current);
        if (isDirty() && !confirm.test("Duplicate the last saved version? Unsaved edits are discarded.")) return;
        WatchDefinition copy = WatchEditorModel.duplicate(stored, UUID.randomUUID().toString());
        current = null;
        repository.upsert(copy);
        selectById(copy.id());
    }

    private void deleteWatch() {
        if (current == null) return;
        if (!confirm.test("Delete watch \"" + current.name() + "\"?")) return;
        String id = current.id();
        current = null;
        repository.delete(id);
        reloadList();
        if (listModel.getRowCount() > 0) watchTable.setRowSelectionInterval(0, 0);
        else showDefinition(null);
    }

    private void selectById(String id) {
        reloadList();
        int index = indexOf(listModel.rows, id);
        if (index >= 0) {
            adjustingSelection = true;
            try {
                watchTable.setRowSelectionInterval(index, index);
            } finally {
                adjustingSelection = false;
            }
            showDefinition(listModel.rows.get(index));
        }
    }

    private void toggleEnabled(int row, boolean enabled) {
        WatchDefinition watch = listModel.rows.get(row);
        WatchDefinition updated = watch.withEnabled(enabled);
        WatchDefinitionValidator.Result result = WatchDefinitionValidator.validate(updated);
        if (!result.valid()) {
            showMessage("Cannot enable \"" + watch.name() + "\":\n" + String.join("\n", result.errors()), true);
            listModel.fireTableRowsUpdated(row, row);
            return;
        }
        if (current != null && current.id().equals(watch.id())) {
            enabledBox.setSelected(enabled);
        }
        repository.upsert(updated);
    }

    // ── Editor ──────────────────────────────────────────────────────────────

    private void showDefinition(WatchDefinition watch) {
        current = watch;
        boolean present = watch != null;
        setEditorEnabled(present);
        snapshotModel.setRowCount(0);
        showMessage("", false);
        WatchEditorModel.EditorState state = WatchEditorModel.toState(
                present ? watch : WatchDefinition.empty("", ""));
        nameField.setText(present ? state.name() : "");
        enabledBox.setSelected(state.enabled());
        methodsField.setText(state.methods());
        hostPatternField.setText(state.hostPattern());
        pathPatternField.setText(state.pathPattern());
        probeHostField.setText(state.probeHost());
        probePortField.setText(state.probePort());
        probeHttpsBox.setSelected(state.probeHttps());
        probeEditor.setText(state.probeRaw());
        inheritHeadersField.setText(state.inheritHeaders());
        variablesModel.setRows(state.variables());
        observationsModel.setRows(state.observations());
        expectationsModel.setRows(state.expectations());
        WatchDisplayOptions display = state.display();
        headersBox.setSelected(display.headers());
        envelopeBox.setSelected(display.jsonEnvelope());
        textBlockBox.setSelected(display.textBlock());
        annotationsBox.setSelected(display.annotations());
        WatchTiming timing = state.timing();
        probeTimeout.setValue((int) timing.probeTimeoutMillis());
        settleInterval.setValue((int) timing.settleIntervalMillis());
        stableCount.setValue(timing.settleStableCount());
        settleMax.setValue((int) timing.settleMaxMillis());
        flowWait.setValue((int) timing.flowWaitMillis());
    }

    private WatchEditorModel.EditorState readState() {
        for (JTable table : List.of(variablesTable, observationsTable, expectationsTable)) stopEditing(table);
        for (JSpinner spinner : List.of(probeTimeout, settleInterval, stableCount, settleMax, flowWait)) {
            try {
                spinner.commitEdit();
            } catch (ParseException ignored) {
                // Keep the last valid value.
            }
        }
        return new WatchEditorModel.EditorState(nameField.getText(), enabledBox.isSelected(), methodsField.getText(),
                hostPatternField.getText(), pathPatternField.getText(), probeHostField.getText(), probePortField.getText(),
                probeHttpsBox.isSelected(), probeEditor.text(), inheritHeadersField.getText(),
                variablesModel.rows(), observationsModel.rows(), expectationsModel.rows(),
                new WatchDisplayOptions(headersBox.isSelected(), envelopeBox.isSelected(), textBlockBox.isSelected(),
                        annotationsBox.isSelected()),
                new WatchTiming(longValue(probeTimeout), longValue(settleInterval), (int) longValue(stableCount),
                        longValue(settleMax), longValue(flowWait)));
    }

    private boolean isDirty() {
        if (current == null) return false;
        try {
            return !WatchEditorModel.apply(current, readState()).equals(current);
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private void save() {
        if (current == null) return;
        WatchDefinition built;
        try {
            built = WatchEditorModel.apply(current, readState());
        } catch (IllegalArgumentException exception) {
            showMessage(exception.getMessage(), true);
            return;
        }
        WatchDefinitionValidator.Result result = WatchDefinitionValidator.validate(built);
        if (!result.valid()) {
            showMessage("Not saved:\n" + String.join("\n", result.errors()), true);
            return;
        }
        try {
            current = built;
            repository.upsert(built);
        } catch (RuntimeException exception) {
            showMessage("Save failed: " + exception.getMessage(), true);
            return;
        }
        showMessage(result.warnings().isEmpty() ? "Saved." : "Saved with warnings:\n" + String.join("\n", result.warnings()),
                !result.warnings().isEmpty());
    }

    private void revert() {
        if (current == null) return;
        showDefinition(repository.find(current.id()).orElse(null));
    }

    private void testProbe() {
        if (current == null || probeTester == null) return;
        WatchDefinition draft;
        try {
            draft = WatchEditorModel.apply(current, readState());
        } catch (IllegalArgumentException exception) {
            showMessage(exception.getMessage(), true);
            return;
        }
        if (!draft.probe().configured()) {
            showMessage("Enter a probe request first.", true);
            return;
        }
        if (probeWorker != null && !probeWorker.isDone()) return;
        testProbeButton.setEnabled(false);
        snapshotModel.setRowCount(0);
        showMessage("Sending probe…", false);
        probeWorker = new SwingWorker<>() {
            @Override
            protected WatchSnapshot doInBackground() {
                return probeTester.apply(draft);
            }

            @Override
            protected void done() {
                testProbeButton.setEnabled(true);
                if (isCancelled()) return;
                try {
                    WatchSnapshot snapshot = get();
                    snapshotModel.setRowCount(0);
                    WatchResultHistory.snapshotRows(snapshot).forEach(snapshotModel::addRow);
                    showMessage("Probe returned HTTP " + snapshot.probeStatusCode() + ": " + snapshot.values().size()
                            + " value(s), " + snapshot.errors().size() + " error(s).", !snapshot.errors().isEmpty());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    showMessage("Probe test failed: " + cause.getMessage(), true);
                }
            }
        };
        probeWorker.execute();
    }

    private void showMessage(String text, boolean problem) {
        messages.setText(text);
        messages.setForeground(problem ? UiTheme.pick(new Color(170, 30, 30), new Color(255, 130, 130))
                : UiTheme.pick(new Color(30, 110, 30), new Color(130, 210, 130)));
    }

    private void setEditorEnabled(boolean enabled) {
        setTreeEnabled(editorTabs, enabled);
        testProbeButton.setEnabled(enabled && probeTester != null);
    }

    private static void setTreeEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) setTreeEnabled(child, enabled);
        }
    }

    // ── Results ─────────────────────────────────────────────────────────────

    private WatchResultHistory.Entry selectedResult() {
        int row = resultsTable.getSelectedRow();
        List<WatchResultHistory.Entry> entries = history.entries();
        return row >= 0 && row < entries.size() ? entries.get(row) : null;
    }

    private void showResultDetail() {
        detailModel.setRowCount(0);
        WatchResultHistory.Entry entry = selectedResult();
        if (entry == null) {
            notesArea.setText("");
            return;
        }
        WatchResultHistory.detailRows(entry.result()).forEach(detailModel::addRow);
        notesArea.setText(WatchResultHistory.notesText(entry.result()));
        notesArea.setCaretPosition(0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static int indexOf(List<WatchDefinition> watches, String id) {
        if (id == null) return -1;
        for (int i = 0; i < watches.size(); i++) {
            if (watches.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static <T> void move(JTable table, RecordTableModel<T> model, int delta) {
        stopEditing(table);
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row < 0 || target < 0 || target >= model.getRowCount()) return;
        model.swap(row, target);
        table.setRowSelectionInterval(target, target);
    }

    private static void stopEditing(JTable table) {
        if (table.isEditing() && table.getCellEditor() != null) table.getCellEditor().stopCellEditing();
    }

    private static long longValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).longValue();
    }

    private static JSpinner spinner(int value, int min, int max, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        spinner.setPreferredSize(new Dimension(110, spinner.getPreferredSize().height));
        return spinner;
    }

    private static JButton button(JButton button, Runnable action) {
        button.addActionListener(event -> action.run());
        return button;
    }

    private static int formRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = field instanceof JTextField ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        form.add(field, c);
        return row + 1;
    }

    private static JComponent wrapNorth(JComponent component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(component, BorderLayout.NORTH);
        return new JScrollPane(panel);
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    // ── Models ──────────────────────────────────────────────────────────────

    private final class WatchListModel extends AbstractTableModel {
        private List<WatchDefinition> rows = List.of();

        void setRows(List<WatchDefinition> watches) {
            rows = List.copyOf(watches);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "On" : "Name";
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            WatchDefinition watch = rows.get(row);
            return column == 0 ? watch.enabled() : watch.name();
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) toggleEnabled(row, Boolean.TRUE.equals(value));
        }
    }

    private final class ResultsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return history.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return List.of("Time", "Watch", "Status", "Summary").get(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            WatchResultHistory.Entry entry = history.entries().get(row);
            return switch (column) {
                case 0 -> WatchResultHistory.time(entry.receivedAtMillis());
                case 1 -> entry.result().watchName();
                case 2 -> entry.result().status();
                default -> WatchResultHistory.summary(entry.result());
            };
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected && value instanceof WatchResult.Status status) {
                component.setForeground(switch (status) {
                    case FAIL -> UiTheme.pick(new Color(180, 20, 20), new Color(255, 120, 120));
                    case ERROR -> UiTheme.pick(new Color(180, 100, 0), new Color(255, 180, 90));
                    case PASS -> UiTheme.pick(new Color(20, 130, 40), new Color(120, 220, 140));
                    case CHANGED -> UiTheme.pick(new Color(30, 80, 170), new Color(130, 170, 255));
                    case NO_CHANGE -> table.getForeground();
                });
            }
            return component;
        }
    }

    /**
     * Editable table over immutable rows; edits go through {@link WatchEditorModel.Column#setter()}.
     *
     * @param <T> row type
     */
    static final class RecordTableModel<T> extends AbstractTableModel {
        private final List<WatchEditorModel.Column<T>> columns;
        private final List<T> rows = new ArrayList<>();

        RecordTableModel(List<WatchEditorModel.Column<T>> columns) {
            this.columns = List.copyOf(columns);
        }

        List<T> rows() {
            return List.copyOf(rows);
        }

        void setRows(List<T> values) {
            rows.clear();
            rows.addAll(values);
            fireTableDataChanged();
        }

        void add(T row) {
            rows.add(row);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void remove(int index) {
            rows.remove(index);
            fireTableRowsDeleted(index, index);
        }

        void swap(int a, int b) {
            T first = rows.get(a);
            rows.set(a, rows.get(b));
            rows.set(b, first);
            fireTableRowsUpdated(Math.min(a, b), Math.max(a, b));
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column).name();
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return columns.get(column).type();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return columns.get(column).getter().apply(rows.get(row));
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            rows.set(row, columns.get(column).setter().apply(rows.get(row), value));
            fireTableRowsUpdated(row, row);
        }
    }

    private interface ProbeEditor {
        String text();

        void setText(String text);

        Component component();
    }

    private static final class TextProbeEditor implements ProbeEditor {
        private final JTextArea area = new JTextArea(12, 50);
        private final JScrollPane scroll = new JScrollPane(area);

        @Override
        public String text() {
            return area.getText();
        }

        @Override
        public void setText(String text) {
            area.setText(text);
        }

        @Override
        public Component component() {
            return scroll;
        }
    }

    private static final class BurpProbeEditor implements ProbeEditor {
        private final HttpRequestEditor editor;
        private String blankText = "";

        BurpProbeEditor(HttpRequestEditor editor) {
            this.editor = editor;
        }

        @Override
        public String text() {
            HttpRequest request = editor.getRequest();
            String text = request == null ? "" : request.toString();
            return text.equals(blankText) ? "" : text;
        }

        @Override
        public void setText(String text) {
            editor.setRequest(HttpRequest.httpRequest(text == null ? "" : text));
            if (text == null || text.isEmpty()) {
                HttpRequest request = editor.getRequest();
                blankText = request == null ? "" : request.toString();
            }
        }

        @Override
        public Component component() {
            return editor.uiComponent();
        }
    }
}
