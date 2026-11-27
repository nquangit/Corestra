package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.ui.component.WrappedText;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthKeepAliveConfig;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer;
import dev.nquangit.corestra.access.auth.analysis.AuthFlowVariableMigrator;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer.AuthInjectionSuggestion;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer.AuthProviderFlowAnalysis;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer.AuthProviderFlowIssue;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer.Severity;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class AuthProvidersPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final List<javax.swing.JButton> editingButtons = new ArrayList<>();
    private boolean editingEnabled = true;

    AuthProvidersPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        tableModel = new DefaultTableModel(new Object[]{
                "Name", "Enabled", "Login Flow", "Refresh Flow", "Validate Flow", "Keep-alive Flow", "Fallback", "Retry", "TTL (s)", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (editingEnabled && event.getClickCount() == 2) {
                    selectedProvider().ifPresent(AuthProvidersPanel.this::showEditor);
                }
            }
        });
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(9));

        add(toolbar(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        refresh();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        javax.swing.JButton add = StyledButton.primary("Add");
        javax.swing.JButton edit = StyledButton.neutral("Edit");
        javax.swing.JButton delete = StyledButton.danger("Delete");
        editingButtons.addAll(List.of(add, edit, delete));
        add.addActionListener(event -> showEditor(null));
        edit.addActionListener(event -> selectedProvider().ifPresent(this::showEditor));
        delete.addActionListener(event -> selectedProvider().ifPresent(this::deleteProvider));
        panel.add(add);
        panel.add(edit);
        panel.add(delete);
        JLabel hint = new JLabel("Provider = shared login behavior; keep user credentials in Auth Profiles.");
        hint.setToolTipText("One provider can serve many actors while each profile keeps isolated credentials and auth state.");
        panel.add(Box.createHorizontalStrut(8));
        panel.add(hint);
        return panel;
    }

    private void showEditor(AuthProvider existing) {
        AuthProviderFormPanel form = new AuthProviderFormPanel(existing == null
                ? null
                : AuthProviderFormModel.from(existing),
                controller.flows(),
                controller.canUpdateFlows() ? controller::updateFlow : null);
        while (true) {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    form,
                    existing == null ? "Add Auth Provider" : "Edit Auth Provider",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                AuthProviderFlowAnalysis analysis = form.analysis();
                if (analysis.hasErrors()) {
                    JOptionPane.showMessageDialog(
                            this,
                            form.blockingErrors(analysis),
                            "Auth Provider has blocking errors",
                            JOptionPane.ERROR_MESSAGE);
                    continue;
                }
                controller.saveAuthProvider(existing == null ? "" : existing.id(), form.toModel());
                refresh();
                return;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Auth Provider", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void deleteProvider(AuthProvider provider) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete auth provider \"" + provider.name() + "\"?",
                "Delete Auth Provider",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                controller.deleteAuthProvider(provider.id());
                refresh();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                JOptionPane.showMessageDialog(this, exception.getMessage(),
                        "Cannot delete Auth Provider", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private java.util.Optional<AuthProvider> selectedProvider() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 9);
        return controller.authProvider(id);
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    void setEditingEnabled(boolean enabled) {
        editingEnabled = enabled;
        editingButtons.forEach(button -> button.setEnabled(enabled));
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (AuthProvider provider : controller.authProviders()) {
            tableModel.addRow(new Object[]{
                    provider.name(),
                    provider.enabled(),
                    flowLabel(provider.loginFlowId()),
                    flowLabel(provider.refreshFlowId()),
                    flowLabel(provider.validateFlowId()),
                    flowLabel(provider.keepAlive().flowId()),
                    provider.fallbackPolicy(),
                    provider.targetRetryPolicy(),
                    provider.stateTtlSeconds(),
                    provider.id()});
        }
    }

    private String flowLabel(String flowId) {
        String normalized = flowId == null ? "" : flowId.strip();
        if (normalized.isBlank()) {
            return "";
        }
        return controller.flows().stream()
                .filter(flow -> flow.id().equals(normalized))
                .map(flow -> flow.name() + " (" + flow.id() + ")")
                .findFirst()
                .orElse(normalized);
    }

    private static final class AuthProviderFormPanel extends JPanel {
        private final JTextField name = new JTextField();
        private final JTextField description = new JTextField();
        private final JCheckBox enabled = new JCheckBox("Enabled", true);
        private final JComboBox<FlowOption> loginFlow = new JComboBox<>();
        private final JComboBox<FlowOption> refreshFlow = new JComboBox<>();
        private final JComboBox<FlowOption> validateFlow = new JComboBox<>();
        private final JComboBox<FlowOption> keepAliveFlow = new JComboBox<>();
        private final JComboBox<AuthFallbackPolicy> fallbackPolicy = new JComboBox<>(AuthFallbackPolicy.values());
        private final JComboBox<AuthRetryPolicy> targetRetryPolicy = new JComboBox<>(AuthRetryPolicy.values());
        private final JSpinner stateTtlSeconds = new JSpinner(new SpinnerNumberModel(
                AuthProvider.DEFAULT_STATE_TTL_SECONDS, 0L, AuthProvider.MAX_STATE_TTL_SECONDS, 30L));
        private final JTextArea contractSummary = new JTextArea(7, 70);
        private final List<FlowDefinition> flows;
        private final Consumer<FlowDefinition> flowUpdater;
        private final AuthProviderFlowAnalyzer analyzer = new AuthProviderFlowAnalyzer();
        private final AuthFlowVariableMigrator migrator = new AuthFlowVariableMigrator();
        private final InjectionRulesTablePanel injectionRules;
        private final AuthOutcomeRulesTable successRules;
        private final AuthOutcomeRulesTable failureRules;
        private final AuthOutcomeRulesTable keepAliveSuccessRules;
        private final AuthOutcomeRulesTable keepAliveExpiredRules;

        AuthProviderFormPanel(AuthProviderFormModel model, List<FlowDefinition> flows) {
            this(model, flows, null);
        }

        AuthProviderFormPanel(
                AuthProviderFormModel model,
                List<FlowDefinition> flows,
                Consumer<FlowDefinition> flowUpdater) {
            super(new BorderLayout(8, 8));
            this.flows = new ArrayList<>(flows == null ? List.of() : flows);
            this.flowUpdater = flowUpdater;
            AuthProviderFormModel initial = model == null
                    ? new AuthProviderFormModel(
                    "New Auth Provider",
                    "",
                    true,
                    "",
                    "",
                    "",
                    AuthFallbackPolicy.LOGIN_ONLY,
                    AuthRetryPolicy.NO_RETRY,
                    AuthProvider.DEFAULT_STATE_TTL_SECONDS,
                    List.of(),
                    List.of(),
                    List.of())
                    : model;
            populateFlowChoices(this.flows, initial.loginFlowId(), initial.refreshFlowId(), initial.validateFlowId());
            addFlowChoices(keepAliveFlow, this.flows, initial.keepAlive().flowId());
            name.setText(initial.name());
            description.setText(initial.description());
            enabled.setSelected(initial.enabled());
            selectFlow(loginFlow, initial.loginFlowId());
            selectFlow(refreshFlow, initial.refreshFlowId());
            selectFlow(validateFlow, initial.validateFlowId());
            selectFlow(keepAliveFlow, initial.keepAlive().flowId());
            fallbackPolicy.setSelectedItem(initial.fallbackPolicy());
            targetRetryPolicy.setSelectedItem(initial.targetRetryPolicy());
            stateTtlSeconds.setValue(initial.stateTtlSeconds());
            injectionRules = new InjectionRulesTablePanel(initial.injectionRules());
            successRules = new AuthOutcomeRulesTable("Success Rules", initial.successRules(), true);
            failureRules = new AuthOutcomeRulesTable("Failure Rules", initial.failureRules(), false);
            keepAliveSuccessRules = new AuthOutcomeRulesTable("Keep-alive Success", initial.keepAlive().successRules(), true);
            keepAliveExpiredRules = new AuthOutcomeRulesTable("Session Expired", initial.keepAlive().expiredRules(), false);

            add(buildTopSection(), BorderLayout.NORTH);
            add(buildRuleTabs(), BorderLayout.CENTER);
            setPreferredSize(new Dimension(900, 720));
            installAnalysisListeners();
            refreshAnalysis();
        }

        AuthProviderFormModel toModel() {
            return new AuthProviderFormModel(
                    name.getText(),
                    description.getText(),
                    enabled.isSelected(),
                    selectedFlowId(loginFlow),
                    selectedFlowId(refreshFlow),
                    selectedFlowId(validateFlow),
                    selectedFallbackPolicy(),
                    selectedRetryPolicy(),
                    ((Number) stateTtlSeconds.getValue()).longValue(),
                    injectionRules.rules(),
                    successRules.successRules(),
                    failureRules.failureRules(), keepAliveConfig());
        }

        private JPanel buildFields() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel("Description"));
            fields.add(description);
            fields.add(new JLabel("Status"));
            fields.add(enabled);
            fields.add(new JLabel("Login Flow"));
            fields.add(loginFlow);
            fields.add(new JLabel("Refresh Flow"));
            fields.add(refreshFlow);
            fields.add(new JLabel("Validate Flow"));
            fields.add(validateFlow);
            fields.add(new JLabel("Keep-alive Flow (optional)"));
            keepAliveFlow.setToolTipText("Scheduler maintains existing sessions with this flow; None keeps the previous auth behavior.");
            fields.add(keepAliveFlow);
            fields.add(new JLabel("Fallback Policy"));
            fields.add(fallbackPolicy);
            JLabel retryLabel = new JLabel("Target Retry Policy");
            retryLabel.setToolTipText("When a target response matches a Failure Rule: refresh/login, then send it once more. "
                    + "Applies to Access Testing, Race runs and live traffic of Session Handler profiles linked to this provider. "
                    + "NO_RETRY leaves the response untouched.");
            targetRetryPolicy.setToolTipText(retryLabel.getToolTipText());
            fields.add(retryLabel);
            fields.add(targetRetryPolicy);
            JLabel ttlLabel = new JLabel("Auth State TTL (seconds)");
            ttlLabel.setToolTipText("Fallback reuse period when login/refresh does not extract expiresAt, expiresIn, or expires_in. Set 0 to authenticate every target.");
            stateTtlSeconds.setToolTipText(ttlLabel.getToolTipText());
            fields.add(ttlLabel);
            fields.add(stateTtlSeconds);

            JTextArea note = new JTextArea(
                    "Select flows that already exist in Flowise. TTL prevents repeated login when the server does not return an expiry. An extracted expiresAt/expiresIn value takes priority.");
            note.setEditable(false);
            note.setOpaque(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);

            panel.add(fields, BorderLayout.CENTER);
            panel.add(note, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildTopSection() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.add(buildFields());

            JPanel contract = new JPanel(new BorderLayout(6, 6));
            contract.setBorder(BorderFactory.createTitledBorder("Flow Contract"));
            contractSummary.setEditable(false);
            contractSummary.setLineWrap(true);
            contractSummary.setWrapStyleWord(true);
            contractSummary.setBackground(javax.swing.UIManager.getColor("TextArea.background"));
            contract.add(new JScrollPane(contractSummary), BorderLayout.CENTER);
            javax.swing.JButton analyze = StyledButton.neutral("Refresh analysis");
            analyze.addActionListener(event -> refreshAnalysis());
            javax.swing.JButton normalize = StyledButton.neutral("Fix auth.* extractor names");
            normalize.setEnabled(flowUpdater != null);
            normalize.setToolTipText(flowUpdater == null
                    ? "Flow editing is unavailable in this UI context."
                    : "Rename safe auth.key extractors to key and update later Flowise injection references.");
            normalize.addActionListener(event -> normalizeSelectedFlows());
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            controls.add(analyze);
            controls.add(normalize);
            controls.add(new JLabel("Extractor key: accessToken  →  provider template: {{auth.accessToken}}"));
            contract.add(controls, BorderLayout.SOUTH);
            panel.add(Box.createVerticalStrut(6));
            panel.add(contract);
            return panel;
        }

        private void normalizeSelectedFlows() {
            if (flowUpdater == null) {
                return;
            }
            LinkedHashSet<String> selectedIds = new LinkedHashSet<>(List.of(
                    selectedFlowId(loginFlow), selectedFlowId(refreshFlow), selectedFlowId(validateFlow),
                    selectedFlowId(keepAliveFlow)));
            selectedIds.remove("");
            int extractors = 0;
            int references = 0;
            ArrayList<String> warnings = new ArrayList<>();
            for (String flowId : selectedIds) {
                FlowDefinition current = flows.stream()
                        .filter(flow -> flow.id().equals(flowId))
                        .findFirst()
                        .orElse(null);
                if (current == null) {
                    continue;
                }
                AuthFlowVariableMigrator.MigrationResult result = migrator.migrate(current);
                warnings.addAll(result.warnings());
                if (result.changed()) {
                    try {
                        flowUpdater.accept(result.flow());
                    } catch (IllegalArgumentException | IllegalStateException exception) {
                        JOptionPane.showMessageDialog(this, exception.getMessage(),
                                "Cannot update auth flow", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    replaceFlow(result.flow());
                    extractors += result.renamedExtractors();
                    references += result.renamedReferences();
                }
            }
            refreshAnalysis();
            String message = "Renamed " + extractors + " extractor(s) and " + references
                    + " later injection reference(s).";
            if (!warnings.isEmpty()) {
                message += "\n\n" + String.join("\n", warnings);
            }
            JOptionPane.showMessageDialog(this, message, "Auth flow normalization",
                    warnings.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        }

        private void replaceFlow(FlowDefinition replacement) {
            for (int index = 0; index < flows.size(); index++) {
                if (flows.get(index).id().equals(replacement.id())) {
                    flows.set(index, replacement);
                    return;
                }
            }
        }

        private void installAnalysisListeners() {
            loginFlow.addActionListener(event -> refreshAnalysis());
            refreshFlow.addActionListener(event -> refreshAnalysis());
            validateFlow.addActionListener(event -> refreshAnalysis());
            keepAliveFlow.addActionListener(event -> refreshAnalysis());
            fallbackPolicy.addActionListener(event -> refreshAnalysis());
            targetRetryPolicy.addActionListener(event -> refreshAnalysis());
            stateTtlSeconds.addChangeListener(event -> refreshAnalysis());
            injectionRules.setChangeListener(this::refreshAnalysis);
        }

        private void refreshAnalysis() {
            AuthProviderFlowAnalysis analysis;
            try {
                analysis = analysis();
            } catch (IllegalArgumentException exception) {
                contractSummary.setForeground(errorColor());
                contractSummary.setText("ERROR: " + exception.getMessage());
                return;
            }
            injectionRules.setSuggestions(analysis.outputKeys(), analysis.suggestions());
            contractSummary.setForeground(analysis.hasErrors()
                    ? errorColor()
                    : javax.swing.UIManager.getColor("TextArea.foreground"));
            contractSummary.setText(formatAnalysis(analysis));
            contractSummary.setCaretPosition(0);
        }

        private Color errorColor() {
            return UiTheme.pick(new Color(180, 45, 45), new Color(255, 125, 125));
        }

        private AuthProviderFlowAnalysis analysis() {
            return analyzer.analyze(analysisProvider(), flows);
        }

        private AuthProvider analysisProvider() {
            return new AuthProvider(
                    "analysis-provider",
                    name.getText().isBlank() ? "Auth Provider" : name.getText(),
                    description.getText(),
                    enabled.isSelected(),
                    selectedFlowId(loginFlow),
                    selectedFlowId(refreshFlow),
                    selectedFlowId(validateFlow),
                    injectionRules.rules(),
                    successRules.successRules(),
                    failureRules.failureRules(),
                    selectedFallbackPolicy(),
                    selectedRetryPolicy(),
                    ((Number) stateTtlSeconds.getValue()).longValue(), keepAliveConfig());
        }

        private String formatAnalysis(AuthProviderFlowAnalysis analysis) {
            StringBuilder text = new StringBuilder();
            text.append("Required inputs: ")
                    .append(analysis.requiredInputs().isEmpty() ? "none" : String.join(", ", analysis.requiredInputs()))
                    .append('\n');
            text.append("Produced Auth State: ");
            if (analysis.outputs().isEmpty()) {
                text.append("none\n");
            } else {
                text.append('\n');
                analysis.outputs().forEach(output -> text.append("  ")
                        .append(output.key())
                        .append(" → {{auth.").append(output.key()).append("}}")
                        .append("  [").append(output.purpose()).append(" / ").append(output.stepName()).append("]\n"));
            }
            if (!analysis.suggestions().isEmpty()) {
                text.append("Suggested target rules: ").append(analysis.suggestions().size()).append('\n');
            }
            for (AuthProviderFlowIssue issue : analysis.issues()) {
                text.append(issue.severity() == Severity.ERROR ? "ERROR: " : "WARNING: ")
                        .append(issue.title()).append(" — ").append(issue.detail()).append('\n');
            }
            return text.toString().stripTrailing();
        }

        private String blockingErrors(AuthProviderFlowAnalysis analysis) {
            return analysis.issues().stream()
                    .filter(issue -> issue.severity() == Severity.ERROR)
                    .map(issue -> "• " + issue.title() + ": " + issue.detail())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        private void populateFlowChoices(
                List<FlowDefinition> flows,
                String loginFlowId,
                String refreshFlowId,
                String validateFlowId) {
            addFlowChoices(loginFlow, flows, loginFlowId);
            addFlowChoices(refreshFlow, flows, refreshFlowId);
            addFlowChoices(validateFlow, flows, validateFlowId);
        }

        private void addFlowChoices(JComboBox<FlowOption> combo, List<FlowDefinition> flows, String selectedId) {
            combo.removeAllItems();
            combo.addItem(FlowOption.none());
            boolean selectedFound = selectedId == null || selectedId.isBlank();
            if (flows != null) {
                for (FlowDefinition flow : flows) {
                    combo.addItem(FlowOption.from(flow));
                    if (flow.id().equals(selectedId)) {
                        selectedFound = true;
                    }
                }
            }
            if (!selectedFound) {
                combo.addItem(FlowOption.missing(selectedId));
            }
        }

        private void selectFlow(JComboBox<FlowOption> combo, String id) {
            String normalized = id == null ? "" : id.strip();
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (combo.getItemAt(index).id().equals(normalized)) {
                    combo.setSelectedIndex(index);
                    return;
                }
            }
            combo.setSelectedIndex(0);
        }

        private String selectedFlowId(JComboBox<FlowOption> combo) {
            Object selected = combo.getSelectedItem();
            return selected instanceof FlowOption option ? option.id() : "";
        }

        private JTabbedPane buildRuleTabs() {
            JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
            tabs.addTab("Injection", injectionRules);
            tabs.addTab("Success", successRules);
            tabs.addTab("Failure", failureRules);
            JPanel keepAlivePanel = new JPanel(new BorderLayout(6, 6));
            javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT,
                    keepAliveSuccessRules, keepAliveExpiredRules);
            split.setResizeWeight(0.5);
            keepAliveSuccessRules.setBorder(BorderFactory.createTitledBorder("Keep-alive Success"));
            keepAliveExpiredRules.setBorder(BorderFactory.createTitledBorder("Session Expired"));
            keepAlivePanel.add(split, BorderLayout.CENTER);
            keepAlivePanel.add(new WrappedText(
                    "Rules are separate from login/refresh. Any Success rule confirms keep-alive; "
                    + "any Session Expired rule permits re-authentication.\nExpired rules take priority. "
                    + "Timeouts and unknown responses retain state and retry with backoff (30s to 5min)."), BorderLayout.SOUTH);
            tabs.addTab("Keep-alive", keepAlivePanel);
            return tabs;
        }

        private AuthKeepAliveConfig keepAliveConfig() {
            return new AuthKeepAliveConfig(selectedFlowId(keepAliveFlow),
                    keepAliveSuccessRules.successRules(), keepAliveExpiredRules.failureRules());
        }

        private AuthFallbackPolicy selectedFallbackPolicy() {
            Object selected = fallbackPolicy.getSelectedItem();
            return selected instanceof AuthFallbackPolicy policy ? policy : AuthFallbackPolicy.MANUAL_ONLY;
        }

        private AuthRetryPolicy selectedRetryPolicy() {
            Object selected = targetRetryPolicy.getSelectedItem();
            return selected instanceof AuthRetryPolicy policy ? policy : AuthRetryPolicy.NO_RETRY;
        }
    }

    private record FlowOption(String id, String label) {
        private static FlowOption none() {
            return new FlowOption("", "None");
        }

        private static FlowOption from(FlowDefinition flow) {
            return new FlowOption(flow.id(), flow.name() + " (" + flow.id() + ")");
        }

        private static FlowOption missing(String id) {
            String normalized = id == null ? "" : id.strip();
            return new FlowOption(normalized, "Missing flow: " + normalized);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class InjectionRulesTablePanel extends JPanel {
        private static final int ID_COLUMN = 5;

        private final DefaultTableModel model;
        private final JTable table;
        private List<AuthInjectionSuggestion> suggestions = List.of();
        private Runnable changeListener = () -> { };

        InjectionRulesTablePanel(List<AuthInjectionRule> rules) {
            super(new BorderLayout(6, 6));
            setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            model = new DefaultTableModel(new Object[]{"Enabled", "Location", "Target", "Value Template", "Mode", "Id"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return true;
                }

                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    return columnIndex == 0 ? Boolean.class : Object.class;
                }
            };
            table = new JTable(model);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(1)
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(new AuthInjectionLocation[]{
                            AuthInjectionLocation.HEADER,
                            AuthInjectionLocation.COOKIE,
                            AuthInjectionLocation.QUERY})));
            table.getColumnModel().getColumn(4)
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(AuthInjectionMode.values())));
            table.getColumnModel().removeColumn(table.getColumnModel().getColumn(ID_COLUMN));

            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
            setRules(rules);
            model.addTableModelListener(event -> changeListener.run());
        }

        void setChangeListener(Runnable listener) {
            changeListener = listener == null ? () -> { } : listener;
        }

        void setSuggestions(List<String> outputKeys, List<AuthInjectionSuggestion> suggestions) {
            this.suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
            JComboBox<String> values = new JComboBox<>();
            values.setEditable(true);
            if (outputKeys != null) {
                for (String key : outputKeys) {
                    values.addItem("{{auth." + key + "}}");
                    String lower = key.toLowerCase(java.util.Locale.ROOT);
                    if (lower.equals("accesstoken") || lower.equals("token") || lower.contains("jwt")) {
                        values.addItem("Bearer {{auth." + key + "}}");
                    }
                }
            }
            table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(values));
        }

        List<AuthInjectionRule> rules() {
            stopEditing();
            ArrayList<AuthInjectionRule> rules = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                String target = stringAt(row, 2);
                String valueTemplate = stringAt(row, 3);
                if (target.isBlank() && valueTemplate.isBlank()) {
                    continue;
                }
                rules.add(new AuthInjectionRule(
                        idAt(row, "injection"),
                        nameFor("Injection", target, valueTemplate),
                        booleanAt(row, 0),
                        enumAt(row, 1, AuthInjectionLocation.class, AuthInjectionLocation.HEADER),
                        target,
                        valueTemplate,
                        enumAt(row, 4, AuthInjectionMode.class, AuthInjectionMode.OVERRIDE)));
            }
            return List.copyOf(rules);
        }

        private void setRules(List<AuthInjectionRule> rules) {
            model.setRowCount(0);
            if (rules != null) {
                rules.forEach(rule -> model.addRow(new Object[]{
                        rule.enabled(),
                        rule.location(),
                        rule.target(),
                        rule.valueTemplate(),
                        rule.mode(),
                        rule.id()}));
            }
        }

        private JPanel buttons() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            javax.swing.JButton add = StyledButton.neutral("Add Row");
            javax.swing.JButton remove = StyledButton.danger("Remove Row");
            javax.swing.JButton bearer = StyledButton.neutral("Bearer preset");
            javax.swing.JButton cookie = StyledButton.neutral("Cookie preset");
            javax.swing.JButton csrf = StyledButton.neutral("CSRF preset");
            javax.swing.JButton generate = StyledButton.primary("Generate from flow");
            add.addActionListener(event -> model.addRow(new Object[]{
                    true,
                    AuthInjectionLocation.HEADER,
                    "Authorization",
                    "Bearer {{auth.accessToken}}",
                    AuthInjectionMode.OVERRIDE,
                    ""}));
            remove.addActionListener(event -> removeSelectedRow());
            bearer.addActionListener(event -> addPreset(AuthInjectionLocation.HEADER,
                    "Authorization", "Bearer {{auth.accessToken}}"));
            cookie.addActionListener(event -> addPreset(AuthInjectionLocation.COOKIE,
                    "SESSION", "{{auth.sessionId}}"));
            csrf.addActionListener(event -> addPreset(AuthInjectionLocation.HEADER,
                    "X-CSRF-Token", "{{auth.csrfToken}}"));
            generate.addActionListener(event -> addSuggestedRules());
            panel.add(add);
            panel.add(remove);
            panel.add(Box.createHorizontalStrut(8));
            panel.add(generate);
            panel.add(bearer);
            panel.add(cookie);
            panel.add(csrf);
            return panel;
        }

        private void addPreset(AuthInjectionLocation location, String target, String template) {
            model.addRow(new Object[]{true, location, target, template, AuthInjectionMode.OVERRIDE, ""});
        }

        private void addSuggestedRules() {
            for (AuthInjectionSuggestion suggestion : suggestions) {
                if (!hasRule(suggestion.location(), suggestion.target())) {
                    addPreset(suggestion.location(), suggestion.target(), suggestion.valueTemplate());
                }
            }
        }

        private boolean hasRule(AuthInjectionLocation location, String target) {
            for (int row = 0; row < model.getRowCount(); row++) {
                if (location == enumAt(row, 1, AuthInjectionLocation.class, AuthInjectionLocation.HEADER)
                        && target.equalsIgnoreCase(stringAt(row, 2))) {
                    return true;
                }
            }
            return false;
        }

        private void removeSelectedRow() {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeRow(table.convertRowIndexToModel(row));
            }
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private String stringAt(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value == null ? "" : value.toString().strip();
        }

        private boolean booleanAt(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }

        private String idAt(int row, String prefix) {
            String id = stringAt(row, ID_COLUMN);
            return id.isBlank() ? prefix + "-" + UUID.randomUUID() : id;
        }

        private <T extends Enum<T>> T enumAt(int row, int column, Class<T> enumType, T defaultValue) {
            Object value = model.getValueAt(row, column);
            if (enumType.isInstance(value)) {
                return enumType.cast(value);
            }
            if (value != null) {
                try {
                    return Enum.valueOf(enumType, value.toString());
                } catch (IllegalArgumentException ignored) {
                    return defaultValue;
                }
            }
            return defaultValue;
        }
    }

    private static String nameFor(String prefix, String first, String second) {
        String value = first == null || first.isBlank() ? second : first;
        return value == null || value.isBlank() ? prefix : prefix + " " + value.strip();
    }
}
