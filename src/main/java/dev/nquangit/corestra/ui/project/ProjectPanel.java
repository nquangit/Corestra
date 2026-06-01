package dev.nquangit.corestra.ui.project;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.persistence.ProjectDocument;
import dev.nquangit.corestra.persistence.ProjectMetadata;
import dev.nquangit.corestra.persistence.ProjectMetadataRepository;
import dev.nquangit.corestra.persistence.ProjectSerializer;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.JFileChooser;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProjectPanel extends JPanel {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final FlowWorkspaceController controller;
    private final ProjectMetadataRepository metadataRepo;
    private final ProjectSerializer projectSerializer;

    private ProjectMetadata currentMeta;

    private final JTextField nameField = new JTextField();
    private final JTextField ownerField = new JTextField();
    private final JTextField descField = new JTextField();
    private final JTextArea noteArea = new JTextArea(2, 20);
    private final JLabel timestampsLabel = new JLabel(" ");
    private final JLabel statsLabel = new JLabel(" ");

    public ProjectPanel(FlowWorkspaceController controller,
                        ProjectMetadataRepository metadataRepo,
                        ProjectSerializer projectSerializer) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.metadataRepo = Objects.requireNonNull(metadataRepo, "metadataRepo");
        this.projectSerializer = Objects.requireNonNull(projectSerializer, "projectSerializer");

        this.currentMeta = metadataRepo.load();

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        content.add(buildFormSection());
        content.add(Box.createVerticalStrut(10));
        content.add(buildTimestampsRow());
        content.add(Box.createVerticalStrut(10));
        content.add(buildStatsSection());
        content.add(Box.createVerticalStrut(14));
        content.add(buildTransferSection());
        content.add(Box.createVerticalGlue());

        add(new JScrollPane(content), BorderLayout.CENTER);

        wireListeners();
        refreshAll();
        controller.addFlowChangeListener(this::refreshStats);
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    private JPanel buildFormSection() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(3, 0, 3, 8);
        lc.gridx = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1;
        fc.insets = new Insets(3, 0, 3, 0);
        fc.gridx = 1;

        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setFont(nameField.getFont());

        lc.gridy = 0; fc.gridy = 0; form.add(new JLabel("Name:"), lc);        form.add(nameField, fc);
        lc.gridy = 1; fc.gridy = 1; form.add(new JLabel("Owner:"), lc);       form.add(ownerField, fc);
        lc.gridy = 2; fc.gridy = 2; form.add(new JLabel("Description:"), lc); form.add(descField, fc);

        lc.gridy = 3; fc.gridy = 3;
        lc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Note:"), lc);
        form.add(new JScrollPane(noteArea), fc);

        return form;
    }

    private JPanel buildTimestampsRow() {
        Font base = UIManager.getFont("Label.font");
        if (base != null) timestampsLabel.setFont(base.deriveFont(base.getSize2D() - 1f));
        timestampsLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel row = new JPanel(new BorderLayout());
        row.add(timestampsLabel, BorderLayout.WEST);
        return row;
    }

    private JPanel buildStatsSection() {
        JLabel header = dimLabel("── Statistics ──────────────────────────────");
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.add(header);
        section.add(Box.createVerticalStrut(4));
        section.add(statsLabel);
        return section;
    }

    private JPanel buildTransferSection() {
        JLabel header = dimLabel("── Transfer ─────────────────────────────────");

        StyledButton importProjectBtn = StyledButton.primary("Import Project");
        StyledButton exportProjectBtn = StyledButton.success("Export Project");
        StyledButton importFlowsBtn   = StyledButton.neutral("Import Flow(s)");
        StyledButton exportFlowsBtn   = StyledButton.neutral("Export Flow(s)");

        importProjectBtn.addActionListener(e -> importProject());
        exportProjectBtn.addActionListener(e -> exportProject());
        importFlowsBtn.addActionListener(e -> importFlows());
        exportFlowsBtn.addActionListener(e -> exportFlows());

        JPanel row1 = new JPanel();
        row1.setLayout(new BoxLayout(row1, BoxLayout.X_AXIS));
        row1.add(importProjectBtn); row1.add(Box.createHorizontalStrut(8)); row1.add(exportProjectBtn);

        JPanel row2 = new JPanel();
        row2.setLayout(new BoxLayout(row2, BoxLayout.X_AXIS));
        row2.add(importFlowsBtn); row2.add(Box.createHorizontalStrut(8)); row2.add(exportFlowsBtn);

        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.add(header);
        section.add(Box.createVerticalStrut(6));
        section.add(row1);
        section.add(Box.createVerticalStrut(4));
        section.add(row2);
        return section;
    }

    private JLabel dimLabel(String text) {
        JLabel lbl = new JLabel(text);
        Font base = UIManager.getFont("Label.font");
        if (base != null) lbl.setFont(base.deriveFont(base.getSize2D() - 0.5f));
        lbl.setForeground(UIManager.getColor("Label.disabledForeground"));
        return lbl;
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private void wireListeners() {
        nameField.addActionListener(e -> saveField(currentMeta.withName(nameField.getText().strip())));
        nameField.addFocusListener(onFocusLost(() -> saveField(currentMeta.withName(nameField.getText().strip()))));

        ownerField.addActionListener(e -> saveField(currentMeta.withOwner(ownerField.getText().strip())));
        ownerField.addFocusListener(onFocusLost(() -> saveField(currentMeta.withOwner(ownerField.getText().strip()))));

        descField.addActionListener(e -> saveField(currentMeta.withDescription(descField.getText().strip())));
        descField.addFocusListener(onFocusLost(() -> saveField(currentMeta.withDescription(descField.getText().strip()))));

        noteArea.addFocusListener(onFocusLost(() -> saveField(currentMeta.withNote(noteArea.getText()))));
    }

    private void saveField(ProjectMetadata updated) {
        currentMeta = updated.withUpdatedAtEpochMillis(System.currentTimeMillis());
        metadataRepo.save(currentMeta);
        refreshTimestamps();
    }

    private static FocusAdapter onFocusLost(Runnable action) {
        return new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { action.run(); }
        };
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private void refreshAll() {
        nameField.setText(currentMeta.name());
        ownerField.setText(currentMeta.owner());
        descField.setText(currentMeta.description());
        noteArea.setText(currentMeta.note());
        refreshTimestamps();
        refreshStats();
    }

    private void refreshTimestamps() {
        timestampsLabel.setText(
                "Created: " + DATE_FORMAT.format(new Date(currentMeta.createdAtEpochMillis()))
                + "  •  Updated: " + DATE_FORMAT.format(new Date(currentMeta.updatedAtEpochMillis())));
    }

    private void refreshStats() {
        List<FlowDefinition> flows = controller.flows();
        int stepCount = flows.stream().mapToInt(f -> f.steps().size()).sum();
        int ruleCount = flows.stream().flatMap(f -> f.steps().stream())
                .mapToInt(s -> s.extractRules().size() + s.injectRules().size()).sum();
        statsLabel.setText("Flows: " + flows.size() + "    Steps: " + stepCount + "    Rules: " + ruleCount);
    }

    // ── Export project ────────────────────────────────────────────────────────

    private void exportProject() {
        long now = System.currentTimeMillis();
        currentMeta = currentMeta.withUpdatedAtEpochMillis(now);
        metadataRepo.save(currentMeta);
        refreshTimestamps();

        ProjectDocument doc = new ProjectDocument(1, currentMeta, controller.flows());
        String json;
        try {
            json = projectSerializer.serializeProject(doc);
        } catch (IllegalArgumentException e) {
            showError("Serialization failed: " + e.getMessage());
            return;
        }

        JFileChooser chooser = jsonChooser(JFileChooser.SAVE_DIALOG);
        String defaultName = currentMeta.name().isBlank() ? "corestra-project"
                : currentMeta.name().replaceAll("[^a-zA-Z0-9._-]", "-");
        chooser.setSelectedFile(new File(defaultName + ".json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        writeFile(chooser.getSelectedFile(), json, "Project exported successfully.");
    }

    // ── Import project ────────────────────────────────────────────────────────

    private void importProject() {
        JFileChooser chooser = jsonChooser(JFileChooser.OPEN_DIALOG);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        String json = readFile(chooser.getSelectedFile());
        if (json == null) return;

        ProjectDocument doc;
        try {
            doc = projectSerializer.deserializeProject(json);
        } catch (IllegalArgumentException e) {
            showError("Invalid project file: " + e.getMessage());
            return;
        }

        ProjectMetadata m = doc.metadata();
        String info = "Importing project \"" + m.name() + "\" by \"" + m.owner()
                + "\" — " + doc.flows().size() + " flow(s).\n\nHow should existing flows be handled?";
        int choice = JOptionPane.showOptionDialog(this, info, "Import Project",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"Merge", "Replace All", "Cancel"}, "Merge");

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;
        applyImportedFlows(doc.flows(), choice == 1);
    }

    // ── Export flows ──────────────────────────────────────────────────────────

    private void exportFlows() {
        List<FlowDefinition> all = controller.flows();
        if (all.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No flows to export.", "Export Flow(s)", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<FlowDefinition> selected = showFlowSelectDialog(all);
        if (selected == null || selected.isEmpty()) return;

        String json;
        try {
            json = projectSerializer.serializeFlows(selected);
        } catch (IllegalArgumentException e) {
            showError("Serialization failed: " + e.getMessage());
            return;
        }

        JFileChooser chooser = jsonChooser(JFileChooser.SAVE_DIALOG);
        chooser.setSelectedFile(new File("flows-export.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        writeFile(chooser.getSelectedFile(), json, selected.size() + " flow(s) exported.");
    }

    // ── Import flows ──────────────────────────────────────────────────────────

    private void importFlows() {
        JFileChooser chooser = jsonChooser(JFileChooser.OPEN_DIALOG);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        String json = readFile(chooser.getSelectedFile());
        if (json == null) return;

        List<FlowDefinition> flows;
        try {
            flows = projectSerializer.deserializeFlows(json);
        } catch (IllegalArgumentException e) {
            showError("Invalid file: " + e.getMessage());
            return;
        }

        String info = "Found " + flows.size() + " flow(s). How should existing flows be handled?";
        int choice = JOptionPane.showOptionDialog(this, info, "Import Flow(s)",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"Merge", "Replace All", "Cancel"}, "Merge");

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;
        applyImportedFlows(flows, choice == 1);
    }

    // ── Shared import logic ───────────────────────────────────────────────────

    private void applyImportedFlows(List<FlowDefinition> imported, boolean replaceAll) {
        if (replaceAll) {
            List<FlowDefinition> current = new ArrayList<>(controller.flows());
            for (FlowDefinition f : current) controller.removeFlow(f);
        } else {
            Set<String> existingIds = controller.flows().stream()
                    .map(FlowDefinition::id).collect(Collectors.toCollection(HashSet::new));
            List<FlowDefinition> resolved = new ArrayList<>();
            for (FlowDefinition f : imported) {
                if (existingIds.contains(f.id())) {
                    f = new FlowDefinition(UUID.randomUUID().toString(), f.name(), f.description(),
                            f.enabled(), f.steps(), f.rules(), f.aggregation(), f.triggerConfig());
                }
                resolved.add(f);
            }
            imported = resolved;
        }
        for (FlowDefinition f : imported) controller.addFlow(f);
        if (!imported.isEmpty()) controller.setActiveFlow(imported.get(0));
        refreshStats();
    }

    // ── Multi-select dialog ───────────────────────────────────────────────────

    private List<FlowDefinition> showFlowSelectDialog(List<FlowDefinition> flows) {
        String[] names = flows.stream().map(FlowDefinition::name).toArray(String[]::new);
        JList<String> list = new JList<>(names);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setSelectionInterval(0, flows.size() - 1);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(300, 200));

        int result = JOptionPane.showConfirmDialog(this, scroll, "Select Flows to Export",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        List<FlowDefinition> selected = new ArrayList<>();
        for (int idx : list.getSelectedIndices()) selected.add(flows.get(idx));
        return selected;
    }

    // ── File I/O helpers ──────────────────────────────────────────────────────

    private static JFileChooser jsonChooser(int type) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogType(type);
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));
        return chooser;
    }

    private void writeFile(File file, String content, String successMessage) {
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, successMessage, "Done", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            showError("Write failed: " + e.getMessage());
        }
    }

    private String readFile(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            showError("Could not read file: " + e.getMessage());
            return null;
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
