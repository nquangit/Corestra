package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.extract.ResponseExtractionSuggestionService;
import dev.nquangit.corestra.ui.component.StyledButton;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reviews response-only extraction suggestions before explicitly creating ordinary editable rules. */
public final class ResponseExtractionDialog {
    private ResponseExtractionDialog() { }

    /** Snapshots the response actually displayed, without binary-to-text transformations or any HTTP send. */
    public static Optional<HttpMessageSnapshot> snapshot(HttpResponse response) {
        if (response == null || response.statusCode() < 100) return Optional.empty();
        return Optional.of(new HttpMessageSnapshot(dev.nquangit.corestra.integration.HttpMessageSnapshots.statusLine(response),
                response.headers().stream().map(h -> h.name() + ": " + h.value()).toList(), response.bodyToString()));
    }

    /** Returns an immutable response reference without reading its body on the UI thread. */
    public static Optional<HttpResponse> available(HttpResponse response) {
        return response == null || response.statusCode() < 100 ? Optional.empty() : Optional.of(response);
    }

    /** Opens a bounded background analysis and a checkbox table; cancelling never changes existing rules. */
    public static void open(Component parent, HttpMessageSnapshot response, List<ExtractRule> existing,
                            Consumer<List<ExtractRule>> apply) {
        open(parent, () -> response, existing, apply);
    }

    /** Reads and analyzes an immutable response off the EDT, then presents the review table on the EDT. */
    public static void open(Component parent, Supplier<HttpMessageSnapshot> response, List<ExtractRule> existing,
                            Consumer<List<ExtractRule>> apply) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Auto extract — response only", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        DefaultTableModel model = new DefaultTableModel(new String[]{"Use", "Type", "Selector", "Sample value", "Variable (editable)"}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0 || column == 4; }
        };
        JTable table = new JTable(model); table.setAutoCreateRowSorter(true); table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(45);
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);
        table.getColumnModel().getColumn(4).setPreferredWidth(170);
        JTextArea notes = new JTextArea("Analyzing the displayed response… No request is sent.", 3, 70);
        notes.setLineWrap(true); notes.setWrapStyleWord(true); notes.setEditable(false); notes.setOpaque(false);
        JPanel content = new JPanel(new BorderLayout(6, 6)); content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(new JLabel("Select fields, edit variable names, then Apply. Samples use the same extraction logic as execution."), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout()); footer.add(notes, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var all = StyledButton.neutral("Select all"); var none = StyledButton.neutral("Clear selection");
        all.addActionListener(e -> { for (int row = 0; row < model.getRowCount(); row++) model.setValueAt(true, row, 0); });
        none.addActionListener(e -> { for (int row = 0; row < model.getRowCount(); row++) model.setValueAt(false, row, 0); });
        var save = StyledButton.primary("Apply selected"); save.setEnabled(false);
        var cancel = StyledButton.neutral("Cancel"); cancel.addActionListener(e -> dialog.dispose());
        buttons.add(all); buttons.add(none); buttons.add(save); buttons.add(cancel); footer.add(buttons, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH); dialog.setContentPane(content);
        List<ResponseExtractionSuggestionService.Candidate> candidates = new ArrayList<>();
        save.addActionListener(e -> {
            try {
                if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
                List<ExtractRule> chosen = new ArrayList<>();
                for (int row = 0; row < model.getRowCount(); row++) if (Boolean.TRUE.equals(model.getValueAt(row, 0)))
                    chosen.add(candidates.get(row).toRule(Objects.toString(model.getValueAt(row, 4), "")));
                if (chosen.isEmpty()) throw new IllegalArgumentException("Select at least one field.");
                apply.accept(chosen); dialog.dispose();
            } catch (RuntimeException invalid) { JOptionPane.showMessageDialog(dialog, invalid.getMessage(), "Extraction rules", JOptionPane.ERROR_MESSAGE); }
        });
        SwingWorker<ResponseExtractionSuggestionService.Analysis, Void> worker = new SwingWorker<>() {
            @Override protected ResponseExtractionSuggestionService.Analysis doInBackground() {
                return new ResponseExtractionSuggestionService().analyze(response.get(), existing);
            }
            @Override protected void done() {
                if (isCancelled() || !dialog.isDisplayable()) return;
                try {
                    var result = get(); candidates.addAll(result.candidates());
                    for (var c : candidates) model.addRow(new Object[]{false, c.type().name(), c.selector(), c.preview(), c.variable()});
                    notes.setText(candidates.size() + " suggestion(s). " + String.join("\n", result.notes()));
                    save.setEnabled(!candidates.isEmpty());
                } catch (Exception failed) { notes.setText("Could not analyze this response. Existing rules are unchanged."); }
            }
        };
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { worker.cancel(true); } });
        dialog.setSize(1000, 560); dialog.setMinimumSize(new Dimension(700, 360)); dialog.setLocationRelativeTo(parent);
        worker.execute(); dialog.setVisible(true);
    }
}
