package dev.nquangit.corestra.ui.project;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.http.discovery.*;
import dev.nquangit.corestra.ui.component.StyledButton;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime vocabulary workspace: categorized wordlists, passive observations, export and saved-history rescan. */
public final class ParameterDictionaryPanel extends JPanel implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ParameterDictionary dictionary;
    private final ParameterDiscoveryHandler handler;
    private final WordlistDictionary words;
    private final ProxyHistoryWordlistRescan rescan;
    private final JTextField filter = new JTextField(20);
    private final JTextField observationFilter = new JTextField(20);
    private final JComboBox<Object> category = new JComboBox<>(), host = new JComboBox<>();
    private final JLabel counts = new JLabel();
    private final JTextField status = new JTextField("Runtime only. Export TXT/ZIP to keep wordlists; Rescan reads saved Proxy History without sending requests.");
    private final WordModel wordModel = new WordModel();
    private final JTable wordTable = new JTable(wordModel);
    private final DefaultTableModel observationModel = new DefaultTableModel(new String[]{"Name", "Location", "Host", "Path", "Count", "Example", "Last seen"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final List<JComponent> idleControls = new ArrayList<>();
    private final JButton cancel = StyledButton.danger("Cancel job");
    private final AtomicBoolean cancelled = new AtomicBoolean(), dirty = new AtomicBoolean(true);
    private final javax.swing.Timer refreshTimer;
    private SwingWorker<?, ?> job;
    private JButton rescanButton;
    private long renderedRevision = -1;
    private int filteredWords, scopedWords;
    private boolean updating, closed;

    /** Creates a standalone workspace without a Proxy History source. */
    public ParameterDictionaryPanel(ParameterDictionary dictionary, ParameterDiscoveryHandler handler) { this(dictionary, handler, null); }

    /** Creates the workspace with a read-only Burp Proxy History source. */
    public ParameterDictionaryPanel(ParameterDictionary dictionary, ParameterDiscoveryHandler handler, MontoyaApi api) {
        super(new BorderLayout(4, 4));
        this.dictionary = Objects.requireNonNull(dictionary); this.handler = Objects.requireNonNull(handler); words = handler.wordlists();
        rescan = api == null ? null : new ProxyHistoryWordlistRescan(() -> api.proxy().history(), handler);
        category.addItem("All categories"); for (var c : WordlistDictionary.Category.values()) category.addItem(c);
        host.addItem("All hosts");
        JPanel controls = new JPanel(); controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JPanel collection = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JCheckBox pause = new JCheckBox("Pause live collection", handler.paused()); pause.addActionListener(e -> handler.setPaused(pause.isSelected()));
        JCheckBox scope = new JCheckBox("In-scope only", handler.inScopeOnly()); scope.addActionListener(e -> handler.setInScopeOnly(scope.isSelected()));
        JCheckBox repeater = new JCheckBox("Include Repeater", handler.includeRepeater()); repeater.addActionListener(e -> handler.setIncludeRepeater(repeater.isSelected()));
        repeater.setToolTipText("Off by default: manually tested payloads can pollute vocabulary. Scanner, Intruder and extension sources are always excluded.");
        JCheckBox values = new JCheckBox("Categorical values", handler.collectValues()); values.addActionListener(e -> handler.setCollectValues(values.isSelected()));
        values.setToolTipText("Short action/mode/type/role/status/format values only. Turning off also clears existing Value words.");
        collection.add(pause); collection.add(scope); collection.add(repeater); collection.add(values);
        idleControls.addAll(List.of(scope, repeater, values));
        collection.add(new JLabel("Scanner / Intruder / Extensions excluded · payload heuristics on")); controls.add(collection);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        rescanButton = button("Rescan Proxy History", this::rescanHistory); actions.add(rescanButton);
        if (rescan == null) { rescanButton.setEnabled(false); rescanButton.setToolTipText("Proxy History source unavailable"); }
        actions.add(cancel); cancel.setEnabled(false); cancel.addActionListener(e -> { cancelled.set(true); status("Cancelling… partial collected words are retained."); });
        actions.add(button("Export filtered TXT…", () -> export(false))); actions.add(button("Export filtered ZIP…", () -> export(true)));
        actions.add(button("Clear runtime data", () -> { handler.clear(); refresh(); status("Both runtime dictionaries cleared. Exported files are unchanged."); }));
        controls.add(actions); add(controls, BorderLayout.NORTH);

        JPanel vocabulary = new JPanel(new BorderLayout()); JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        filters.add(new JLabel("Category")); filters.add(category); filters.add(new JLabel("Host")); filters.add(host); filters.add(new JLabel("Word filter")); filters.add(filter);
        filters.add(button("Remove selected", this::removeSelected)); vocabulary.add(filters, BorderLayout.NORTH);
        wordTable.setAutoCreateRowSorter(true); wordTable.setFillsViewportHeight(true); wordTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        vocabulary.add(new JScrollPane(wordTable), BorderLayout.CENTER); vocabulary.add(counts, BorderLayout.SOUTH);
        JTable observations = new JTable(observationModel); observations.setAutoCreateRowSorter(true); observations.setFillsViewportHeight(true);
        JPanel inventory = new JPanel(new BorderLayout());
        JCheckBox examples = new JCheckBox("Store redacted examples (observations only)", handler.collectExamples());
        examples.addActionListener(e -> handler.setCollectExamples(examples.isSelected())); idleControls.add(examples);
        JPanel observationControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        observationControls.add(new JLabel("Filter:")); observationControls.add(observationFilter); observationControls.add(examples);
        inventory.add(observationControls, BorderLayout.NORTH); inventory.add(new JScrollPane(observations), BorderLayout.CENTER);
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Wordlists", vocabulary); tabs.addTab("Observations", inventory); add(tabs, BorderLayout.CENTER);
        status.setEditable(false); add(status, BorderLayout.SOUTH);
        category.addActionListener(e -> { if (!updating) refresh(); }); host.addActionListener(e -> { if (!updating) refresh(); });
        DocumentListener changes = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { dirty.set(true); }
            @Override public void removeUpdate(DocumentEvent e) { dirty.set(true); }
            @Override public void changedUpdate(DocumentEvent e) { dirty.set(true); }
        };
        filter.getDocument().addDocumentListener(changes); observationFilter.getDocument().addDocumentListener(changes);
        dictionary.addListener(() -> dirty.set(true));
        refreshTimer = new javax.swing.Timer(500, e -> {
            if (!isShowing()) return;
            if (dirty.get() || words.revision() != renderedRevision) refresh(); else refreshCounts();
        });
        refreshTimer.start(); refresh();
    }

    private JButton button(String label, Runnable action) {
        JButton b = StyledButton.neutral(label); idleControls.add(b);
        b.addActionListener(e -> { if (job != null || closed) return; try { action.run(); } catch (RuntimeException failure) { error(failure); } }); return b;
    }
    private WordlistDictionary.Category selectedCategory() { return category.getSelectedItem() instanceof WordlistDictionary.Category c ? c : null; }
    private String selectedHost() { return host.getSelectedIndex() <= 0 ? "" : Objects.toString(host.getSelectedItem()); }

    private void refresh() {
        if (closed) return;
        long revision = words.revision(); dirty.set(false);
        List<WordlistDictionary.Entry> snapshot = words.snapshot();
        Object previous = host.getSelectedItem(); List<String> hosts = snapshot.stream().map(WordlistDictionary.Entry::host).distinct().sorted().toList();
        List<String> renderedHosts = new ArrayList<>(); for (int i = 1; i < host.getItemCount(); i++) renderedHosts.add(host.getItemAt(i).toString());
        if (!hosts.equals(renderedHosts)) {
            updating = true; host.removeAllItems(); host.addItem("All hosts"); hosts.forEach(host::addItem);
            if (hosts.contains(previous)) host.setSelectedItem(previous); updating = false;
        }
        var groups = WordlistDictionary.grouped(snapshot, selectedCategory(), selectedHost(), filter.getText());
        Set<String> selected = new HashSet<>();
        for (int row : wordTable.getSelectedRows()) { WordRow r = wordModel.rows.get(wordTable.convertRowIndexToModel(row)); selected.add(r.category().name() + ":" + r.word()); }
        List<WordRow> rows = new ArrayList<>(); int total = groups.values().stream().mapToInt(List::size).sum();
        for (var c : WordlistDictionary.Category.values()) for (String word : groups.getOrDefault(c, List.of())) {
            if (rows.size() >= 5000) break;
            rows.add(new WordRow(c, word));
        }
        wordModel.rows = List.copyOf(rows); wordModel.fireTableDataChanged();
        for (int index = 0; index < rows.size(); index++) if (selected.contains(rows.get(index).category().name() + ":" + rows.get(index).word())) {
            int view = wordTable.convertRowIndexToView(index); if (view >= 0) wordTable.addRowSelectionInterval(view, view);
        }
        filteredWords = total; scopedWords = snapshot.size(); refreshCounts();
        observationModel.setRowCount(0);
        String observationQuery = observationFilter.getText().strip().toLowerCase(Locale.ROOT);
        for (var e : dictionary.snapshot()) {
            if (!(e.name() + " " + e.location() + " " + e.host() + " " + e.path()).toLowerCase(Locale.ROOT).contains(observationQuery)) continue;
            observationModel.addRow(new Object[]{e.name(), e.location(), e.host(), e.path(), e.count(), e.example(), TIME.format(e.lastSeen())});
        }
        renderedRevision = revision;
    }

    private void refreshCounts() {
        String text = " " + filteredWords + " unique words in filtered categories · " + scopedWords + "/" + WordlistDictionary.DEFAULT_CAPACITY
                + " scoped entries · " + words.evicted() + " evicted · " + handler.dropped() + " queue/processing drops · " + handler.skipped() + " skipped messages"
                + (filteredWords > wordModel.rows.size() ? " · showing first 5,000; export includes all matches" : "");
        if (!text.equals(counts.getText())) counts.setText(text);
    }

    private void removeSelected() {
        Set<WordRow> selected = new HashSet<>(); for (int row : wordTable.getSelectedRows()) selected.add(wordModel.rows.get(wordTable.convertRowIndexToModel(row)));
        if (selected.isEmpty()) return;
        String h = selectedHost();
        words.remove(words.snapshot().stream().filter(e -> selected.contains(new WordRow(e.category(), e.word())) && (h.isBlank() || h.equals(e.host()))).toList());
        refresh(); status("Selected words removed from the current host filter. Future matching traffic/rescan can add them again.");
    }

    private void rescanHistory() {
        if (rescan == null) return;
        cancelled.set(false); busy(true); status("Reading saved Proxy History… no request will be sent.");
        SwingWorker<ProxyHistoryWordlistRescan.Result, Integer> scan = new SwingWorker<>() {
            @Override protected ProxyHistoryWordlistRescan.Result doInBackground() { return rescan.scan(cancelled::get, this::publish); }
            @Override protected void process(List<Integer> counts) { if (!closed) status("Rescan: examined " + counts.getLast() + " saved records…"); }
            @Override protected void done() {
                if (closed) return;
                try { var r = get(); status((r.cancelled() ? "Cancelled" : "Rescan complete") + ": " + r.examined() + " examined, " + r.collected() + " collected, " + r.skipped() + " skipped. No requests sent."); }
                catch (Exception failed) { error(failed); }
                finally { job = null; busy(false); refresh(); }
            }
        };
        job = scan; scan.execute();
    }

    private void export(boolean zip) {
        var groups = WordlistDictionary.grouped(words.snapshot(), selectedCategory(), selectedHost(), filter.getText());
        if (groups.isEmpty()) throw new IllegalArgumentException("No matching words to export");
        if (!zip && selectedCategory() == null) throw new IllegalArgumentException("Choose one Category for TXT, or export ZIP for separate categorized files.");
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(zip ? "corestra-wordlists.zip" : selectedCategory().fileName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(this, "Replace " + target.getFileName() + "?", "Export wordlists", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        busy(true); cancel.setEnabled(false); status("Exporting filtered words…");
        SwingWorker<Void, Void> export = new SwingWorker<>() {
            @Override protected Void doInBackground() throws Exception {
                if (zip) WordlistExporter.zip(target, groups); else WordlistExporter.text(target, groups.values().iterator().next()); return null;
            }
            @Override protected void done() {
                if (closed) return;
                try { get(); status("Exported " + groups.values().stream().mapToInt(List::size).sum() + " words to " + target); }
                catch (Exception failed) { error(failed); } finally { job = null; busy(false); }
            }
        };
        job = export; export.execute();
    }
    private void busy(boolean value) { idleControls.forEach(c -> c.setEnabled(!value)); cancel.setEnabled(value); if (rescan == null) rescanButton.setEnabled(false); }
    private void status(String message) { status.setText(message); status.setCaretPosition(0); status.setToolTipText(message); }
    private void error(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause(); status("Failed: " + cause.getMessage());
        JOptionPane.showMessageDialog(this, cause.getMessage(), "Parameters / Wordlists", JOptionPane.ERROR_MESSAGE);
    }
    /** Stops UI timers/jobs; all vocabulary remains transient and the collector is closed separately. */
    @Override public void close() { closed = true; cancelled.set(true); refreshTimer.stop(); if (job != null) job.cancel(true); }
    private record WordRow(WordlistDictionary.Category category, String word) { }
    private static final class WordModel extends AbstractTableModel {
        private List<WordRow> rows = List.of();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int column) { return column == 0 ? "Word (unique per category)" : "Category"; }
        @Override public Object getValueAt(int row, int column) { return column == 0 ? rows.get(row).word() : rows.get(row).category(); }
    }
}
