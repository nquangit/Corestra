package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.execution.FlowExecutionLog;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Searchable flow execution timeline with native Burp request and response viewers. */
public final class FlowLogPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private final FlowWorkspaceController controller;
    private final FlowExecutionLog log;
    private final JTextField filter = new JTextField(24);
    private final DefaultTableModel model;
    private final JTable table;
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final JCheckBox allFlows = new JCheckBox("All flows (incl. child flows)");
    private final JTextField details = new JTextField("Select an exchange to inspect its actual request and response.");
    private List<FlowExecutionLog.Entry> visibleEntries = List.of();
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean dirty = true;
    private boolean updatingRows;
    private boolean viewersInitialized;
    private FlowExecutionLog.Entry displayedEntry;
    private java.util.Map<String, String> stepNames = java.util.Map.of();

    /** Creates a log panel for the active flow. */
    public FlowLogPanel(FlowWorkspaceController controller, FlowExecutionLog log, MontoyaApi api) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.log = Objects.requireNonNull(log, "log");
        Objects.requireNonNull(api, "api");
        requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        model = new DefaultTableModel(new String[]{"Time", "Flow", "Request", "Step", "Status", "Code", "ms"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        int[] widths = {95, 130, 300, 150, 110, 50, 60};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !updatingRows) showSelected(); });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        controls.add(new JLabel("Filter:"));
        controls.add(filter);
        StyledButton clear = StyledButton.danger("Clear log");
        clear.addActionListener(event -> log.clear());
        controls.add(clear);
        clear.setToolTipText("Retains up to 1,000 steps / 32 MiB; older entries are evicted. Clearing the log does not reset runtime variables.");
        controls.add(allFlows); allFlows.addActionListener(e -> refresh());
        JComboBox<String> arrangement = new JComboBox<>(new String[]{"Side by side", "Stacked"});
        controls.add(arrangement);

        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                labeled("Actual request sent", requestEditor.uiComponent()), labeled("Response received", responseEditor.uiComponent()));
        viewers.setResizeWeight(0.5);
        viewers.setBorder(BorderFactory.createEmptyBorder());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), viewers);
        split.setResizeWeight(0.0);
        split.setOneTouchExpandable(true);
        split.setDividerLocation(155);
        split.getTopComponent().setMinimumSize(new java.awt.Dimension(0, 60));
        viewers.setMinimumSize(new java.awt.Dimension(0, 100));
        arrangement.addActionListener(e -> {
            viewers.setOrientation(arrangement.getSelectedIndex() == 0 ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT);
            viewers.setDividerLocation(0.5);
        });
        split.setBorder(BorderFactory.createEmptyBorder());
        add(controls, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        details.setEditable(false); add(details, BorderLayout.SOUTH);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        controller.addFlowChangeListener(this::requestRefresh);
        log.addListener(this::requestRefresh);
        addHierarchyListener(event -> {
            if (isShowing() && dirty) requestRefresh();
        });
    }

    private void requestRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                refreshQueued.set(false);
                dirty = true;
                refresh();
            });
        }
    }

    private void refresh() {
        if (!isShowing()) { dirty = true; return; }
        dirty = false;
        var names = new java.util.HashMap<String, String>();
        controller.flows().forEach(f -> f.steps().forEach(s -> names.put(f.id() + ":" + s.id(), s.name())));
        boolean namesChanged = !names.equals(stepNames); stepNames = names;
        FlowDefinition active = controller.activeFlow();
        String query = filter.getText().strip().toLowerCase(Locale.ROOT);
        List<FlowExecutionLog.Entry> filtered = new ArrayList<>();
        for (FlowExecutionLog.Entry entry : log.snapshot()) {
            if (!allFlows.isSelected() && active != null && !active.id().equals(entry.flowId())) continue;
            String searchable = entry.flowName() + " " + stepName(entry) + " " + requestLine(entry) + " " + entry.stepId() + " " + entry.statusCode()
                    + " " + entry.errorMessage();
            if (!query.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(query)) continue;
            filtered.add(entry);
        }
        if (visibleEntries.equals(filtered) && viewersInitialized && !namesChanged) return;
        FlowExecutionLog.Entry selected = displayedEntry;
        updatingRows = true;
        visibleEntries = List.copyOf(filtered);
        model.setRowCount(0);
        for (FlowExecutionLog.Entry entry : visibleEntries) {
            model.addRow(new Object[]{
                    entry.startedAtEpochMillis() == 0 ? "" : TIME.format(Instant.ofEpochMilli(entry.startedAtEpochMillis())),
                    entry.flowName(), requestLine(entry), stepName(entry), (entry.success() ? "PASS" : "FAIL")
                            + (entry.errorMessage().contains("Raw HTTP omitted from Flow Log:") ? " (payload omitted)" : ""),
                    entry.statusCode() == 0 ? "-" : entry.statusCode(), entry.durationMillis()});
        }
        if (!visibleEntries.isEmpty()) {
            int row = selected == null ? -1 : visibleEntries.indexOf(selected);
            table.setRowSelectionInterval(Math.max(0, row), Math.max(0, row));
        }
        updatingRows = false;
        if (!visibleEntries.isEmpty()) showSelected();
        else clearViewers();
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleEntries.size()) return;
        FlowExecutionLog.Entry entry = visibleEntries.get(row);
        if (viewersInitialized && entry.equals(displayedEntry)) return;
        displayedEntry = entry;
        viewersInitialized = true;
        String summary = "Run " + entry.runId() + " | Step " + entry.stepId() + " | " + entry.durationMillis() + " ms"
                + (entry.errorMessage().isBlank() ? "" : " | " + entry.errorMessage());
        details.setText(summary); details.setCaretPosition(0); details.setToolTipText(summary);
        try {
            requestEditor.setRequest(HttpRequest.httpRequest(entry.request().isBlank()
                    ? "GET / HTTP/1.1\r\nHost: unavailable\r\n\r\n" : entry.request()));
        } catch (RuntimeException exception) {
            requestEditor.setRequest(HttpRequest.httpRequest(
                    "GET / HTTP/1.1\r\nHost: unavailable\r\n\r\n"));
        }
        try {
            responseEditor.setResponse(HttpResponse.httpResponse(entry.response()));
        } catch (RuntimeException exception) {
            responseEditor.setResponse(HttpResponse.httpResponse(""));
        }
    }

    private void clearViewers() {
        if (viewersInitialized && displayedEntry == null) return;
        displayedEntry = null;
        viewersInitialized = true;
        details.setText("No matching exchanges. Run a request, change the filter, or enable All flows to include child flows.");
        details.setToolTipText(null);
        requestEditor.setRequest(HttpRequest.httpRequest("GET / HTTP/1.1\r\nHost: unavailable\r\n\r\n"));
        responseEditor.setResponse(HttpResponse.httpResponse(""));
    }

    private String stepName(FlowExecutionLog.Entry entry) {
        return stepNames.getOrDefault(entry.flowId() + ":" + entry.stepId(), entry.stepId());
    }

    private static String requestLine(FlowExecutionLog.Entry entry) {
        String request = entry.request();
        int newline = request.indexOf('\n');
        return request.substring(0, Math.min(512, newline < 0 ? request.length() : newline)).strip();
    }

    private static JPanel labeled(String title, java.awt.Component editor) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(title), BorderLayout.NORTH); panel.add(editor, BorderLayout.CENTER);
        panel.setMinimumSize(new java.awt.Dimension(0, 0)); panel.setPreferredSize(new java.awt.Dimension(100, 100));
        return panel;
    }
}
