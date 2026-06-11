package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.importer.ImportError;
import dev.nquangit.corestra.access.importer.ImportFormat;
import dev.nquangit.corestra.access.importer.ImportOptions;
import dev.nquangit.corestra.access.importer.ImportResult;
import dev.nquangit.corestra.access.importer.ImportSource;
import dev.nquangit.corestra.access.importer.ImportWarning;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationResult;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.template.TemplateRenderIssue;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class TemplatesPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea original = new JTextArea();
    private final JTextArea rendered = new JTextArea();
    private final DefaultTableModel issueModel;
    private final JComboBox<ComboItem> auth = new JComboBox<>();
    private final JComboBox<ComboItem> data = new JComboBox<>();
    private final JCheckBox compatibility = new JCheckBox("Compatibility mode");
    private final KeyValueTablePanel env = new KeyValueTablePanel(java.util.Map.of());
    private final KeyValueTablePanel scenario = new KeyValueTablePanel(java.util.Map.of());
    private final KeyValueTablePanel legacy = new KeyValueTablePanel(java.util.Map.of());

    TemplatesPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        tableModel = new DefaultTableModel(
                new Object[]{"Name", "Method", "Host", "Path / Query", "Source Flow", "Source Step", "Operation", "Enabled", "Id"},
                0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(8));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelectedOriginal();
            }
        });
        installTemplateContextMenu();

        issueModel = new DefaultTableModel(new Object[]{"Location", "Placeholder", "Expression", "Type", "Message"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        original.setEditable(false);
        rendered.setEditable(false);

        add(toolbar(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        refresh();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        javax.swing.JButton add = StyledButton.primary("Add Manual Template");
        javax.swing.JButton importTemplates = StyledButton.neutral("Import Templates");
        javax.swing.JButton delete = StyledButton.danger("Delete");
        javax.swing.JButton preview = StyledButton.success("Preview");
        add.addActionListener(event -> showManualTemplateDialog());
        importTemplates.addActionListener(event -> showImportTemplatesDialog());
        delete.addActionListener(event -> selectedTemplate().ifPresent(this::deleteTemplate));
        preview.addActionListener(event -> renderPreview());
        panel.add(add);
        panel.add(importTemplates);
        panel.add(delete);
        panel.add(preview);
        panel.add(new JLabel("Auth"));
        panel.add(auth);
        panel.add(new JLabel("Data"));
        panel.add(data);
        panel.add(compatibility);
        return panel;
    }

    private void installTemplateContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem previewItem = new JMenuItem("Preview with Selected Auth/Data");
        previewItem.addActionListener(event -> renderPreview());
        JMenuItem ownerRowsItem = new JMenuItem("Create Owner-vs-Others Matrix Rows");
        ownerRowsItem.addActionListener(event -> createOwnerVsOthersRows());
        menu.add(previewItem);
        menu.add(ownerRowsItem);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShow(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShow(event);
            }

            private void maybeShow(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                }
                menu.show(event.getComponent(), event.getX(), event.getY());
            }
        });
        table.setComponentPopupMenu(menu);
    }

    private JSplitPane content() {
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);

        JTabbedPane previewInputs = new JTabbedPane();
        previewInputs.addTab("Env", env);
        previewInputs.addTab("Scenario", scenario);
        previewInputs.addTab("Legacy", legacy);

        JSplitPane textSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                labelled("Original", new JScrollPane(original)),
                labelled("Rendered Preview", new JScrollPane(rendered)));
        textSplit.setResizeWeight(0.5);

        JPanel issuePanel = new JPanel(new BorderLayout());
        issuePanel.setBorder(BorderFactory.createTitledBorder("Unresolved Variables"));
        issuePanel.add(new JScrollPane(new JTable(issueModel)), BorderLayout.CENTER);

        JSplitPane previewSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, textSplit, issuePanel);
        previewSplit.setResizeWeight(0.72);

        JPanel previewPanel = new JPanel(new BorderLayout(8, 8));
        previewPanel.add(previewInputs, BorderLayout.NORTH);
        previewPanel.add(previewSplit, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, previewPanel);
        split.setResizeWeight(0.38);
        return split;
    }

    private void showManualTemplateDialog() {
        ManualTemplatePanel form = new ManualTemplatePanel();
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "Add Manual Template",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.saveManualTemplate(form.name(), form.request(), form.metadata());
            refresh();
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Template", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showImportTemplatesDialog() {
        ImportTemplatesOptionsPanel optionsPanel = new ImportTemplatesOptionsPanel();
        int optionResult = JOptionPane.showConfirmDialog(
                this,
                optionsPanel,
                "Import Templates",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (optionResult != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            ImportResult result = controller.previewImportedTemplates(optionsPanel.toSource(), optionsPanel.toOptions());
            if (result.empty()) {
                JOptionPane.showMessageDialog(
                        this,
                        new ImportDiagnosticsPanel(result),
                        "Import Templates",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            ImportTemplatesPreviewPanel previewPanel = new ImportTemplatesPreviewPanel(result);
            int previewResult = JOptionPane.showConfirmDialog(
                    this,
                    previewPanel,
                    "Preview Imported Templates",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (previewResult != JOptionPane.OK_OPTION) {
                return;
            }
            ImportResult saved = controller.saveImportedTemplates(previewPanel.selectedTemplates());
            refresh();
            JOptionPane.showMessageDialog(
                    this,
                    "Saved " + saved.templates().size() + " imported template(s).",
                    "Templates Imported",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Import Templates", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteTemplate(AccessRequestTemplate template) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete template \"" + template.name() + "\"?",
                "Delete Template",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            controller.deleteTemplate(template.id());
            refresh();
        }
    }

    private void renderPreview() {
        AccessRequestTemplate template = selectedTemplate().orElse(null);
        ComboItem authItem = (ComboItem) auth.getSelectedItem();
        if (template == null || authItem == null || authItem.id().isBlank()) {
            JOptionPane.showMessageDialog(this, "Select a template and auth profile before previewing.",
                    "Preview", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ComboItem dataItem = (ComboItem) data.getSelectedItem();
        try {
            AccessPreview preview = controller.preview(
                    template.id(),
                    authItem.id(),
                    dataItem == null ? "" : dataItem.id(),
                    env.values(),
                    scenario.values(),
                    legacy.values(),
                    compatibility.isSelected() ? RenderMode.COMPATIBILITY : RenderMode.STRICT);
            original.setText(preview.originalSummary());
            rendered.setText(preview.renderedSummary());
            issueModel.setRowCount(0);
            for (TemplateRenderIssue issue : preview.renderedRequest().issues()) {
                issueModel.addRow(new Object[]{
                        issue.location(),
                        issue.unresolvedVariable().placeholder(),
                        issue.unresolvedVariable().expression(),
                        issue.unresolvedVariable().errorType(),
                        issue.unresolvedVariable().message()});
            }
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Preview Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createOwnerVsOthersRows() {
        AccessRequestTemplate template = selectedTemplate().orElse(null);
        if (template == null) {
            JOptionPane.showMessageDialog(this, "Select a template first.",
                    "Create Matrix Rows", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<ExpectedResult> adminExpected = new JComboBox<>(ExpectedResult.values());
        adminExpected.setSelectedItem(ExpectedResult.REVIEW);
        JPanel form = new JPanel(new BorderLayout(6, 6));
        JTextArea summary = new JTextArea(
                "Create disabled owner-vs-others matrix rows for:\n"
                        + template.name()
                        + "\n\nNo HTTP request will be sent.");
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setOpaque(false);
        form.add(summary, BorderLayout.NORTH);
        JPanel choice = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        choice.add(new JLabel("Admin expected"));
        choice.add(adminExpected);
        form.add(choice, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "Create Owner-vs-Others Rows",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            MatrixGenerationResult saved = controller.createOwnerVsOthersRowsForTemplate(
                    template.id(),
                    (ExpectedResult) adminExpected.getSelectedItem());
            JOptionPane.showMessageDialog(
                    this,
                    "Saved " + saved.rows().size() + " disabled row(s). "
                            + saved.duplicateCount() + " duplicate(s) skipped.",
                    "Matrix Rows Created",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Create Matrix Rows", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showSelectedOriginal() {
        selectedTemplate().ifPresentOrElse(template -> {
            original.setText(summary(template.baseRequest()));
            rendered.setText("");
            issueModel.setRowCount(0);
        }, () -> {
            original.setText("");
            rendered.setText("");
            issueModel.setRowCount(0);
        });
    }

    private java.util.Optional<AccessRequestTemplate> selectedTemplate() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 8);
        return controller.templates().stream().filter(template -> template.id().equals(id)).findFirst();
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (AccessRequestTemplate template : controller.templates()) {
            StoredRequestTemplate request = template.baseRequest();
            tableModel.addRow(new Object[]{
                    template.name(),
                    request.method(),
                    request.host(),
                    request.pathAndQuery(),
                    template.sourceFlowId(),
                    template.sourceStepId(),
                    template.operationMetadata().operationType(),
                    template.enabled(),
                    template.id()});
        }
        refreshCombos();
        showSelectedOriginal();
    }

    private void refreshCombos() {
        String selectedAuth = selectedComboId(auth);
        String selectedData = selectedComboId(data);
        auth.removeAllItems();
        data.removeAllItems();
        data.addItem(new ComboItem("", ""));
        for (AuthProfile profile : controller.authProfiles()) {
            auth.addItem(new ComboItem(profile.id(), profile.name()));
        }
        for (DataProfile profile : controller.dataProfiles()) {
            data.addItem(new ComboItem(profile.id(), profile.name()));
        }
        select(auth, selectedAuth);
        select(data, selectedData);
    }

    private static JPanel labelled(String title, JScrollPane child) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(child, BorderLayout.CENTER);
        return panel;
    }

    private static String selectedComboId(JComboBox<ComboItem> combo) {
        ComboItem selected = (ComboItem) combo.getSelectedItem();
        return selected == null ? "" : selected.id();
    }

    private static void select(JComboBox<ComboItem> combo, String id) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).id().equals(id)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
    }

    private static String summary(StoredRequestTemplate request) {
        StringBuilder value = new StringBuilder();
        value.append(request.method()).append(' ')
                .append(request.scheme()).append("://")
                .append(request.host()).append(':')
                .append(request.port())
                .append(request.pathAndQuery()).append('\n');
        for (String header : request.headerLines()) {
            value.append(header).append('\n');
        }
        if (!request.body().isEmpty()) {
            value.append('\n').append(request.body());
        }
        return value.toString();
    }

    private record ComboItem(String id, String label) {
        @Override
        public String toString() {
            return label == null || label.isBlank() ? id : label;
        }
    }

    private static final class ImportTemplatesOptionsPanel extends JPanel {
        private final JComboBox<ImportFormat> format = new JComboBox<>(ImportFormat.values());
        private final JTextField filePath = new JTextField();
        private final JTextField defaultScheme = new JTextField("https");
        private final JTextField defaultHost = new JTextField("example.test");
        private final JTextField defaultPort = new JTextField("443");
        private final JTextField basePathOverride = new JTextField();
        private final JCheckBox disabledByDefault = new JCheckBox("Disabled by default", true);
        private final JCheckBox pathParams = new JCheckBox("Generate path param placeholders", true);
        private final JCheckBox queryParams = new JCheckBox("Generate query param placeholders");
        private final JCheckBox bodyExamples = new JCheckBox("Generate body example placeholders", true);
        private final JCheckBox deprecated = new JCheckBox("Include deprecated operations");

        ImportTemplatesOptionsPanel() {
            super(new BorderLayout(8, 8));
            javax.swing.JButton browse = StyledButton.neutral("Choose File");
            browse.addActionListener(event -> chooseFile());
            JPanel file = new JPanel(new BorderLayout(6, 0));
            file.add(filePath, BorderLayout.CENTER);
            file.add(browse, BorderLayout.EAST);

            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Import Format"));
            fields.add(format);
            fields.add(new JLabel("File"));
            fields.add(file);
            fields.add(new JLabel("Default Scheme"));
            fields.add(defaultScheme);
            fields.add(new JLabel("Default Host"));
            fields.add(defaultHost);
            fields.add(new JLabel("Default Port"));
            fields.add(defaultPort);
            fields.add(new JLabel("Base Path Override"));
            fields.add(basePathOverride);

            JPanel checks = new JPanel(new GridLayout(0, 1, 4, 2));
            checks.add(disabledByDefault);
            checks.add(pathParams);
            checks.add(queryParams);
            checks.add(bodyExamples);
            checks.add(deprecated);

            JTextArea note = new JTextArea(
                    "Imported templates are previewed before saving. No HTTP request will be sent.");
            note.setEditable(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);
            note.setOpaque(false);

            add(note, BorderLayout.NORTH);
            add(fields, BorderLayout.CENTER);
            add(checks, BorderLayout.SOUTH);
        }

        ImportSource toSource() {
            String pathText = filePath.getText();
            if (pathText == null || pathText.isBlank()) {
                throw new IllegalArgumentException("Import file is required");
            }
            try {
                Path path = Path.of(pathText.strip());
                return new ImportSource(
                        path.getFileName().toString(),
                        Files.readString(path, StandardCharsets.UTF_8),
                        (ImportFormat) format.getSelectedItem());
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("Could not read import file: " + exception.getMessage(), exception);
            }
        }

        ImportOptions toOptions() {
            return new ImportOptions(
                    defaultScheme.getText(),
                    defaultHost.getText(),
                    Integer.parseInt(defaultPort.getText().strip()),
                    basePathOverride.getText(),
                    disabledByDefault.isSelected(),
                    deprecated.isSelected(),
                    pathParams.isSelected(),
                    queryParams.isSelected(),
                    bodyExamples.isSelected());
        }

        private void chooseFile() {
            JFileChooser chooser = new JFileChooser();
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                filePath.setText(chooser.getSelectedFile().toPath().toString());
            }
        }
    }

    private static final class ImportTemplatesPreviewPanel extends JPanel {
        private final List<AccessRequestTemplate> templates;
        private final DefaultTableModel model;

        ImportTemplatesPreviewPanel(ImportResult result) {
            super(new BorderLayout(8, 8));
            this.templates = List.copyOf(result.templates());
            model = new DefaultTableModel(
                    new Object[]{
                            "Add",
                            "Name",
                            "Method",
                            "Host",
                            "Path",
                            "Operation",
                            "Placeholders",
                            "Diagnostics",
                            "Enabled"},
                    0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 0 || column == 8;
                }

                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    if (columnIndex == 0 || columnIndex == 8) {
                        return Boolean.class;
                    }
                    if (columnIndex == 6) {
                        return Integer.class;
                    }
                    return String.class;
                }
            };
            for (AccessRequestTemplate template : templates) {
                StoredRequestTemplate request = template.baseRequest();
                model.addRow(new Object[]{
                        Boolean.TRUE,
                        template.name(),
                        request.method(),
                        request.host(),
                        request.pathAndQuery(),
                        template.operationMetadata().operationType(),
                        template.placeholders().size(),
                        diagnosticsSummary(result),
                        template.enabled()});
            }
            add(new JScrollPane(new JTable(model)), BorderLayout.CENTER);
            add(new ImportDiagnosticsPanel(result), BorderLayout.SOUTH);
        }

        List<AccessRequestTemplate> selectedTemplates() {
            ArrayList<AccessRequestTemplate> selected = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < model.getRowCount(); rowIndex++) {
                if (!Boolean.TRUE.equals(model.getValueAt(rowIndex, 0))) {
                    continue;
                }
                AccessRequestTemplate original = templates.get(rowIndex);
                boolean enabled = Boolean.TRUE.equals(model.getValueAt(rowIndex, 8));
                selected.add(copyWithEnabled(original, enabled));
            }
            return List.copyOf(selected);
        }

        private AccessRequestTemplate copyWithEnabled(AccessRequestTemplate original, boolean enabled) {
            return new AccessRequestTemplate(
                    original.id(),
                    original.name(),
                    original.baseRequest(),
                    original.placeholders(),
                    original.operationMetadata(),
                    original.sourceFlowId(),
                    original.sourceStepId(),
                    original.sourceRequestHash(),
                    enabled);
        }

        private String diagnosticsSummary(ImportResult result) {
            int total = result.warnings().size() + result.errors().size();
            if (total == 0) {
                return "";
            }
            return total + " diagnostic(s)";
        }
    }

    private static final class ImportDiagnosticsPanel extends JPanel {
        ImportDiagnosticsPanel(ImportResult result) {
            super(new BorderLayout(4, 4));
            JTextArea diagnostics = new JTextArea(text(result), 6, 72);
            diagnostics.setEditable(false);
            diagnostics.setLineWrap(true);
            diagnostics.setWrapStyleWord(true);
            add(new JScrollPane(diagnostics), BorderLayout.CENTER);
        }

        private static String text(ImportResult result) {
            StringBuilder text = new StringBuilder();
            text.append("Templates: ").append(result.templates().size()).append('\n');
            if (result.skippedCount() > 0) {
                text.append("Skipped: ").append(result.skippedCount()).append('\n');
            }
            for (ImportWarning warning : result.warnings()) {
                text.append("Warning");
                if (!warning.location().isBlank()) {
                    text.append(" [").append(warning.location()).append(']');
                }
                text.append(": ").append(warning.message()).append('\n');
            }
            for (ImportError error : result.errors()) {
                text.append("Error");
                if (!error.location().isBlank()) {
                    text.append(" [").append(error.location()).append(']');
                }
                text.append(": ").append(error.message()).append('\n');
            }
            return text.toString();
        }
    }

    private static final class ManualTemplatePanel extends JPanel {
        private final JTextField name = new JTextField("Manual Template");
        private final JTextField method = new JTextField("GET");
        private final JTextField scheme = new JTextField("https");
        private final JTextField host = new JTextField("example.test");
        private final JTextField port = new JTextField("443");
        private final JTextField path = new JTextField("/");
        private final JTextArea headers = new JTextArea(6, 48);
        private final JTextArea body = new JTextArea(8, 48);
        private final JComboBox<OperationType> operation = new JComboBox<>(OperationType.values());
        private final JCheckBox stateChanging = new JCheckBox("State changing");
        private final JCheckBox consumable = new JCheckBox("Consumable");
        private final JTextField description = new JTextField();

        ManualTemplatePanel() {
            super(new BorderLayout(8, 8));
            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel("Method"));
            fields.add(method);
            fields.add(new JLabel("Scheme"));
            fields.add(scheme);
            fields.add(new JLabel("Host"));
            fields.add(host);
            fields.add(new JLabel("Port"));
            fields.add(port);
            fields.add(new JLabel("Path / Query"));
            fields.add(path);
            fields.add(new JLabel("Operation Type"));
            fields.add(operation);
            fields.add(new JLabel(""));
            fields.add(stateChanging);
            fields.add(new JLabel(""));
            fields.add(consumable);
            fields.add(new JLabel("Description"));
            fields.add(description);

            JPanel text = new JPanel(new GridLayout(0, 1, 6, 6));
            text.add(labelledArea("Headers, one per line", headers));
            text.add(labelledArea("Body", body));

            add(fields, BorderLayout.NORTH);
            add(text, BorderLayout.CENTER);
        }

        String name() {
            return name.getText();
        }

        StoredRequestTemplate request() {
            List<String> lines = Arrays.stream(headers.getText().split("\\R"))
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
            return new StoredRequestTemplate(
                    method.getText(),
                    scheme.getText(),
                    host.getText(),
                    Integer.parseInt(port.getText().strip()),
                    path.getText(),
                    lines,
                    body.getText());
        }

        OperationMetadata metadata() {
            return new OperationMetadata(
                    (OperationType) operation.getSelectedItem(),
                    stateChanging.isSelected(),
                    consumable.isSelected(),
                    description.getText());
        }

        private static JPanel labelledArea(String title, JTextArea area) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createTitledBorder(title));
            panel.add(new JScrollPane(area), BorderLayout.CENTER);
            return panel;
        }
    }
}
