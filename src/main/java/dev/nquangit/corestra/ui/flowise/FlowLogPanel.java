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
    private List<FlowExecutionLog.Entry> visibleEntries = List.of();

    /** Creates a log panel for the active flow. */
    public FlowLogPanel(FlowWorkspaceController controller, FlowExecutionLog log, MontoyaApi api) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.log = Objects.requireNonNull(log, "log");
        Objects.requireNonNull(api, "api");
        requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        model = new DefaultTableModel(new String[]{"Time", "Flow", "Step", "Status", "Code", "Duration"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> showSelected());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        controls.add(new JLabel("Filter:"));
        controls.add(filter);
        StyledButton clear = StyledButton.danger("Clear log");
        clear.addActionListener(event -> log.clear());
        controls.add(clear);

        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                requestEditor.uiComponent(), responseEditor.uiComponent());
        viewers.setResizeWeight(0.5);
        viewers.setBorder(BorderFactory.createEmptyBorder());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), viewers);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(controls, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        controller.addFlowChangeListener(this::refresh);
        log.addListener(() -> {
            if (SwingUtilities.isEventDispatchThread()) refresh();
            else SwingUtilities.invokeLater(this::refresh);
        });
        refresh();
    }

    private void refresh() {
        FlowDefinition active = controller.activeFlow();
        String query = filter.getText().strip().toLowerCase(Locale.ROOT);
        List<FlowExecutionLog.Entry> filtered = new ArrayList<>();
        for (FlowExecutionLog.Entry entry : log.snapshot()) {
            if (active != null && !active.id().equals(entry.flowId())) continue;
            String searchable = entry.flowName() + " " + entry.stepId() + " " + entry.statusCode()
                    + " " + entry.errorMessage();
            if (!query.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(query)) continue;
            filtered.add(entry);
        }
        visibleEntries = List.copyOf(filtered);
        model.setRowCount(0);
        for (FlowExecutionLog.Entry entry : visibleEntries) {
            model.addRow(new Object[]{
                    entry.startedAtEpochMillis() == 0 ? "" : TIME.format(Instant.ofEpochMilli(entry.startedAtEpochMillis())),
                    entry.flowName(), entry.stepId(), entry.success() ? "PASS" : "FAIL",
                    entry.statusCode() == 0 ? "-" : entry.statusCode(), entry.durationMillis()});
        }
        if (!visibleEntries.isEmpty()) table.setRowSelectionInterval(0, 0);
        else clearViewers();
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleEntries.size()) return;
        FlowExecutionLog.Entry entry = visibleEntries.get(row);
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
        requestEditor.setRequest(HttpRequest.httpRequest("GET / HTTP/1.1\r\nHost: unavailable\r\n\r\n"));
        responseEditor.setResponse(HttpResponse.httpResponse(""));
    }
}
