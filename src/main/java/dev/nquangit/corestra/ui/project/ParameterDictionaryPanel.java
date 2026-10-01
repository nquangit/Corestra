package dev.nquangit.corestra.ui.project;

import dev.nquangit.corestra.http.discovery.ParameterDictionary;
import dev.nquangit.corestra.http.discovery.ParameterDiscoveryHandler;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** Runtime parameter dictionary collected passively from Burp traffic. */
public final class ParameterDictionaryPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final ParameterDictionary dictionary;
    private final ParameterDiscoveryHandler handler;
    private final JTextField filter = new JTextField(20);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Name", "Location", "Host", "Path", "Count", "Example", "Last seen"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    /** Creates the dictionary view and collector controls. */
    public ParameterDictionaryPanel(ParameterDictionary dictionary, ParameterDiscoveryHandler handler) {
        super(new BorderLayout());
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.handler = Objects.requireNonNull(handler, "handler");
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);

        JCheckBox scopeOnly = new JCheckBox("In-scope only", handler.inScopeOnly());
        scopeOnly.addActionListener(event -> handler.setInScopeOnly(scopeOnly.isSelected()));
        JCheckBox examples = new JCheckBox("Store redacted examples", handler.collectExamples());
        examples.setToolTipText("Examples remain runtime-only; sensitive keys are replaced with <redacted>");
        examples.addActionListener(event -> handler.setCollectExamples(examples.isSelected()));
        StyledButton clear = StyledButton.danger("Clear");
        clear.addActionListener(event -> dictionary.clear());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(new JLabel("Filter:"));
        controls.add(filter);
        controls.add(scopeOnly);
        controls.add(examples);
        controls.add(clear);
        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        dictionary.addListener(() -> {
            if (SwingUtilities.isEventDispatchThread()) refresh();
            else SwingUtilities.invokeLater(this::refresh);
        });
        refresh();
    }

    private void refresh() {
        String query = filter.getText().strip().toLowerCase(Locale.ROOT);
        model.setRowCount(0);
        for (ParameterDictionary.Entry entry : dictionary.snapshot()) {
            String searchable = entry.name() + " " + entry.location() + " " + entry.host() + " " + entry.path();
            if (!query.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(query)) continue;
            model.addRow(new Object[]{entry.name(), entry.location(), entry.host(), entry.path(), entry.count(),
                    entry.example(), TIME.format(entry.lastSeen())});
        }
    }
}
