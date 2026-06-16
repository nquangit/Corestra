package dev.nquangit.corestra.ui.project;

import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.persistence.ProjectDocument;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectMetadata;
import dev.nquangit.corestra.persistence.ProjectMetadataRepository;
import dev.nquangit.corestra.persistence.ProjectSerializer;
import dev.nquangit.corestra.persistence.ProjectSettings;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import dev.nquangit.corestra.session.SessionProfileRepository;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
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

/**
 * Project metadata and import/export panel.
 */
public final class ProjectPanel extends JPanel {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final ExportMode DEFAULT_ACCESS_EXPORT_MODE = ExportMode.SANITIZED_EXPORT;

    private final FlowWorkspaceController controller;
    private final ProjectMetadataRepository metadataRepo;
    private final ProjectSerializer projectSerializer;
    private final ProjectSettingsRepository settingsRepo;
    private final InMemoryAccessRepository accessRepository;
    private final SessionProfileRepository sessionRepository;
    private final Runnable accessRefreshCallback;

    private ProjectMetadata currentMeta;
    private ProjectSettings currentSettings;

    private final JTextField nameField   = new JTextField();
    private final JTextField ownerField  = new JTextField();
    private final JTextField descField   = new JTextField();
    private final JTextArea  noteArea    = new JTextArea(3, 20);
    private final JLabel timestampsLabel = new JLabel(" ");
    private final JLabel statsLabel      = new JLabel(" ");
    private final JRadioButton standardSendRadio = new JRadioButton("Standard");
    private final JRadioButton proxyPipelineRadio = new JRadioButton("Proxy Pipeline");
    private final JTextField proxyHostField = new JTextField();
    private final JTextField proxyPortField = new JTextField();
    private final JComboBox<ExportMode> accessExportModeCombo = new JComboBox<>(
            new ExportMode[]{ExportMode.FULL_EXPORT, ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS});
    private boolean refreshingSettingsControls;

    /**
     * Creates a project panel without access repository integration.
     *
     * @param controller flow workspace controller
     * @param metadataRepo project metadata repository
     * @param projectSerializer project serializer
     */
    public ProjectPanel(FlowWorkspaceController controller,
                        ProjectMetadataRepository metadataRepo,
                        ProjectSerializer projectSerializer) {
        this(controller, metadataRepo, projectSerializer, new ProjectSettingsRepository(),
                new InMemoryAccessRepository(), new SessionProfileRepository(), () -> { });
    }

    /**
     * Creates a project panel.
     *
     * @param controller flow workspace controller
     * @param metadataRepo project metadata repository
     * @param projectSerializer project serializer
     * @param settingsRepo project settings repository
     * @param accessRepository access workspace repository
     * @param sessionRepository session handler profile repository
     * @param accessRefreshCallback callback invoked after access workspace import
     */
    public ProjectPanel(FlowWorkspaceController controller,
                        ProjectMetadataRepository metadataRepo,
                        ProjectSerializer projectSerializer,
                        ProjectSettingsRepository settingsRepo,
                        InMemoryAccessRepository accessRepository,
                        SessionProfileRepository sessionRepository,
                        Runnable accessRefreshCallback) {
        super(new BorderLayout());
        this.controller        = Objects.requireNonNull(controller,        "controller");
        this.metadataRepo      = Objects.requireNonNull(metadataRepo,      "metadataRepo");
        this.projectSerializer = Objects.requireNonNull(projectSerializer, "projectSerializer");
        this.settingsRepo      = Objects.requireNonNull(settingsRepo,      "settingsRepo");
        this.accessRepository  = Objects.requireNonNull(accessRepository,  "accessRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.accessRefreshCallback = Objects.requireNonNull(accessRefreshCallback, "accessRefreshCallback");

        this.currentMeta = metadataRepo.load();
        this.currentSettings = settingsRepo.load();

        styleSecondary(timestampsLabel);
        styleSecondary(statsLabel);
        accessExportModeCombo.setSelectedItem(DEFAULT_ACCESS_EXPORT_MODE);
        accessExportModeCombo.setToolTipText(exportModeDescription(currentAccessExportMode()));
        accessExportModeCombo.addActionListener(event ->
                accessExportModeCombo.setToolTipText(exportModeDescription(currentAccessExportMode())));

        // All content anchored at the top; CENTER stays empty to absorb extra height.
        add(buildContent(), BorderLayout.NORTH);

        wireListeners();
        refreshAll();
        controller.addFlowChangeListener(this::refreshStats);
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private JPanel buildContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx   = 0;
        gbc.weightx = 1;
        gbc.fill    = GridBagConstraints.HORIZONTAL;
        gbc.anchor  = GridBagConstraints.NORTHWEST;

        int row = 0;

        gbc.gridy = row++; gbc.insets = new Insets(0, 0, 4, 0);
        panel.add(buildFormSection(), gbc);

        gbc.gridy = row++; gbc.insets = new Insets(0, 2, 10, 0);
        panel.add(timestampsLabel, gbc);

        gbc.gridy = row++; gbc.insets = new Insets(0, 0, 8, 0);
        panel.add(new JSeparator(), gbc);

        gbc.gridy = row++; gbc.insets = new Insets(0, 2, 8, 0);
        panel.add(statsLabel, gbc);

        gbc.gridy = row++; gbc.insets = new Insets(0, 0, 10, 0);
        panel.add(new JSeparator(), gbc);

        gbc.gridy = row++; gbc.insets = new Insets(0, 0, 10, 0);
        panel.add(buildSettingsSection(), gbc);

        gbc.gridy = row++; gbc.insets = new Insets(0, 0, 10, 0);
        panel.add(new JSeparator(), gbc);

        gbc.gridy = row; gbc.insets = new Insets(0, 0, 0, 0);
        panel.add(buildTransferSection(), gbc);

        return panel;
    }

    private JPanel buildFormSection() {
        JPanel form = new JPanel(new GridBagLayout());

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(3, 0, 3, 8);
        lc.gridx  = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill    = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1;
        fc.insets  = new Insets(3, 0, 3, 0);
        fc.gridx   = 1;

        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setFont(nameField.getFont());

        lc.gridy = 0; fc.gridy = 0;
        form.add(new JLabel("Name:"), lc);        form.add(nameField, fc);

        lc.gridy = 1; fc.gridy = 1;
        form.add(new JLabel("Owner:"), lc);       form.add(ownerField, fc);

        lc.gridy = 2; fc.gridy = 2;
        form.add(new JLabel("Description:"), lc); form.add(descField, fc);

        lc.gridy = 3; fc.gridy = 3;
        lc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Note:"), lc);
        form.add(new JScrollPane(noteArea), fc);

        return form;
    }

    private JPanel buildSettingsSection() {
        ButtonGroup sendModeGroup = new ButtonGroup();
        sendModeGroup.add(standardSendRadio);
        sendModeGroup.add(proxyPipelineRadio);

        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(BorderFactory.createTitledBorder("Settings"));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(3, 6, 3, 10);
        lc.gridx = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1;
        fc.insets = new Insets(3, 0, 3, 8);
        fc.gridx = 1;

        JPanel modeRow = new JPanel();
        modeRow.setLayout(new BoxLayout(modeRow, BoxLayout.X_AXIS));
        modeRow.add(standardSendRadio);
        modeRow.add(Box.createHorizontalStrut(8));
        modeRow.add(proxyPipelineRadio);
        modeRow.add(Box.createHorizontalGlue());

        lc.gridy = 0; fc.gridy = 0;
        section.add(new JLabel("HTTP send mode:"), lc);
        section.add(modeRow, fc);

        lc.gridy = 1; fc.gridy = 1;
        section.add(new JLabel("Proxy host:"), lc);
        section.add(proxyHostField, fc);

        lc.gridy = 2; fc.gridy = 2;
        section.add(new JLabel("Proxy port:"), lc);
        section.add(proxyPortField, fc);

        return section;
    }

    private JPanel buildTransferSection() {
        StyledButton importProjectBtn = StyledButton.primary("Import Project");
        StyledButton exportProjectBtn = StyledButton.success("Export Project");
        StyledButton importFlowsBtn   = StyledButton.neutral("Import Flow(s)");
        StyledButton exportFlowsBtn   = StyledButton.neutral("Export Flow(s)");

        importProjectBtn.addActionListener(e -> importProject());
        exportProjectBtn.addActionListener(e -> exportProject());
        importFlowsBtn.addActionListener(e -> importFlows());
        exportFlowsBtn.addActionListener(e -> exportFlows());

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(importProjectBtn);
        row.add(Box.createHorizontalStrut(6));
        row.add(exportProjectBtn);
        row.add(Box.createHorizontalStrut(10));
        row.add(new JLabel("Access export:"));
        row.add(Box.createHorizontalStrut(4));
        row.add(accessExportModeCombo);
        row.add(Box.createHorizontalStrut(16));
        row.add(importFlowsBtn);
        row.add(Box.createHorizontalStrut(6));
        row.add(exportFlowsBtn);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private void styleSecondary(JLabel lbl) {
        Font base = UIManager.getFont("Label.font");
        if (base != null) lbl.setFont(base.deriveFont(base.getSize2D() - 0.5f));
        lbl.setForeground(UIManager.getColor("Label.disabledForeground"));
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

        standardSendRadio.addActionListener(e -> saveSettingsFromControls());
        proxyPipelineRadio.addActionListener(e -> saveSettingsFromControls());
        proxyHostField.addActionListener(e -> saveSettingsFromControls());
        proxyHostField.addFocusListener(onFocusLost(this::saveSettingsFromControls));
        proxyPortField.addActionListener(e -> saveSettingsFromControls());
        proxyPortField.addFocusListener(onFocusLost(this::saveSettingsFromControls));
    }

    private void saveField(ProjectMetadata updated) {
        currentMeta = updated.withUpdatedAtEpochMillis(System.currentTimeMillis());
        metadataRepo.save(currentMeta);
        refreshTimestamps();
    }

    private void saveSettingsFromControls() {
        if (refreshingSettingsControls) {
            return;
        }
        int proxyPort;
        try {
            proxyPort = Integer.parseInt(proxyPortField.getText().strip());
            if (proxyPort < 1 || proxyPort > 65535) {
                throw new NumberFormatException("out of range");
            }
        } catch (NumberFormatException exception) {
            JOptionPane.showMessageDialog(this, "Proxy port must be a number from 1 to 65535.",
                    "Invalid Project Settings", JOptionPane.ERROR_MESSAGE);
            refreshSettingsControls();
            return;
        }

        currentSettings = new ProjectSettings(
                proxyPipelineRadio.isSelected() ? HttpSendMode.PROXY_PIPELINE : HttpSendMode.STANDARD,
                proxyHostField.getText(),
                proxyPort);
        settingsRepo.save(currentSettings);
        touchProject();
        refreshSettingsControls();
    }

    private void touchProject() {
        currentMeta = currentMeta.withUpdatedAtEpochMillis(System.currentTimeMillis());
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
        refreshSettingsControls();
        refreshTimestamps();
        refreshStats();
    }

    private void refreshSettingsControls() {
        refreshingSettingsControls = true;
        try {
            standardSendRadio.setSelected(currentSettings.sendMode() == HttpSendMode.STANDARD);
            proxyPipelineRadio.setSelected(currentSettings.sendMode() == HttpSendMode.PROXY_PIPELINE);
            proxyHostField.setText(currentSettings.proxyHost());
            proxyPortField.setText(Integer.toString(currentSettings.proxyPort()));
            boolean proxyEnabled = currentSettings.sendMode() == HttpSendMode.PROXY_PIPELINE;
            proxyHostField.setEnabled(proxyEnabled);
            proxyPortField.setEnabled(proxyEnabled);
        } finally {
            refreshingSettingsControls = false;
        }
    }

    private void refreshTimestamps() {
        timestampsLabel.setText(
                "Created: " + DATE_FORMAT.format(new Date(currentMeta.createdAtEpochMillis()))
                + "   •   Updated: " + DATE_FORMAT.format(new Date(currentMeta.updatedAtEpochMillis())));
    }

    private void refreshStats() {
        List<FlowDefinition> flows = controller.flows();
        int stepCount = flows.stream().mapToInt(f -> f.steps().size()).sum();
        int ruleCount = flows.stream().flatMap(f -> f.steps().stream())
                .mapToInt(s -> s.extractRules().size() + s.injectRules().size()).sum();
        statsLabel.setText("Flows: " + flows.size()
                + "   •   Steps: " + stepCount
                + "   •   Rules: " + ruleCount);
    }

    // ── Export project ────────────────────────────────────────────────────────

    private void exportProject() {
        long now = System.currentTimeMillis();
        currentMeta = currentMeta.withUpdatedAtEpochMillis(now);
        metadataRepo.save(currentMeta);
        refreshTimestamps();

        ExportMode exportMode = currentAccessExportMode();
        if (exportMode == ExportMode.FULL_EXPORT && !confirmFullAccessExport()) {
            return;
        }

        ProjectDocument doc = new ProjectDocument(
                1,
                currentMeta,
                currentSettings,
                controller.flows(),
                accessRepository.snapshotDocument().workspace(),
                accessRepository.snapshotDocument().evidence(),
                accessRepository.snapshotDocument().latestSummary().orElse(null),
                accessRepository.snapshotDocument().reportDrafts(),
                sessionRepository.loadProfiles());
        String json;
        try {
            json = projectSerializer.serializeProject(doc, exportMode);
        } catch (IllegalArgumentException e) {
            showError("Serialization failed: " + e.getMessage());
            return;
        }

        JFileChooser chooser = jsonChooser(JFileChooser.SAVE_DIALOG);
        String defaultName = currentMeta.name().isBlank() ? "corestra-project"
                : currentMeta.name().replaceAll("[^a-zA-Z0-9._-]", "-");
        chooser.setSelectedFile(new File(defaultName + ".json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        writeFile(chooser.getSelectedFile(), json, exportSuccessMessage(exportMode));
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
                + "\" — " + doc.flows().size() + " flow(s), "
                + doc.accessWorkspace().authProviders().size() + " auth provider(s), "
                + doc.accessWorkspace().authProfiles().size() + " auth profile(s), "
                + doc.accessWorkspace().dataProfiles().size() + " data profile(s), "
                + doc.accessWorkspace().templates().size() + " access template(s), "
                + doc.accessWorkspace().evaluationProfiles().size() + " evaluation profile(s), "
                + doc.accessWorkspace().testCases().size() + " matrix row(s), "
                + doc.accessWorkspace().results().size() + " latest result(s), "
                + doc.accessEvidence().size() + " evidence item(s), "
                + doc.accessReportDrafts().size() + " saved report draft(s), "
                + doc.sessionProfiles().size() + " session profile(s), "
                + "send mode " + doc.settings().sendMode() + ".\n\n"
                + "Runtime scenarios are not stored in project files.\n\n"
                + "How should existing flows be handled?";
        int choice = JOptionPane.showOptionDialog(this, info, "Import Project",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"Merge", "Replace All", "Cancel"}, "Merge");

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;
        currentSettings = doc.settings();
        settingsRepo.save(currentSettings);
        refreshSettingsControls();
        touchProject();
        applyImportedFlows(doc.flows(), choice == 1);
        accessRepository.replaceWith(doc.accessDocument());
        sessionRepository.saveProfiles(doc.sessionProfiles());
        accessRefreshCallback.run();
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

    private ExportMode currentAccessExportMode() {
        Object selected = accessExportModeCombo.getSelectedItem();
        return selected instanceof ExportMode exportMode ? exportMode : DEFAULT_ACCESS_EXPORT_MODE;
    }

    private boolean confirmFullAccessExport() {
        String message = "This export may include access tokens, cookies, credentials, "
                + "and sensitive object IDs in Access Testing configuration.";
        int choice = JOptionPane.showConfirmDialog(this, message, "Full Access Export",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    private static String exportSuccessMessage(ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> "Project exported successfully. Access configuration secrets may be included.";
            case SANITIZED_EXPORT -> "Project exported successfully. Access secrets were redacted.";
            case EXCLUDE_SECRETS -> "Project exported successfully. Access auth state was omitted.";
            case ENCRYPTED_EXPORT -> "Project exported successfully.";
        };
    }

    private static String exportModeDescription(ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> "Includes access tokens, cookies, and credentials in reusable Access Testing configuration.";
            case SANITIZED_EXPORT -> "Redacts secrets in reusable Access Testing configuration.";
            case EXCLUDE_SECRETS -> "Omits auth state while keeping safe Access Testing metadata.";
            case ENCRYPTED_EXPORT -> "Encrypted access export is reserved for a future release.";
        };
    }
}
