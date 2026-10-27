package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.inject.InjectionSuggestionService;

import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.access.AccessUiController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Displays the extract and inject rules for the currently selected {@link FlowStep}.
 *
 * <p>Each rule type is shown in its own table with Add, Edit, and Remove buttons.
 * Double-clicking a row opens the {@link RuleEditorDialog} for that rule. Rules are persisted
 * via the {@link FlowWorkspaceController} on every add, edit, or remove action.</p>
 *
 * <p>This class is not thread-safe. All mutations must occur on the Swing Event Dispatch Thread.</p>
 */
public final class RuleSummaryPanel extends JPanel {

    private static final String[] EXTRACT_COLUMNS = {
            "Name", "Source", "Type", "Regex Input", "Selector", "Transforms", "→ Variable"};
    private static final String[] INJECT_COLUMNS  = {
            "Name", "Target", "Selector", "Source", "Transforms", "Strategy"};

    private final FlowWorkspaceController controller;
    private final Supplier<Optional<RequestResponsePanel.ResponseRegexSelection>> regexSelectionSupplier;
    private final AccessUiController accessController;
    private final Supplier<Optional<burp.api.montoya.http.message.responses.HttpResponse>> responseSupplier;

    private final DefaultTableModel extractModel;
    private final DefaultTableModel injectModel;
    private final JTable extractTable;
    private final JTable injectTable;

    /**
     * Creates a rule summary panel wired to the supplied controller.
     *
     * @param controller the workspace controller that owns flow and step state
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public RuleSummaryPanel(FlowWorkspaceController controller) {
        this(controller, Optional::empty, null);
    }

    /**
     * Creates a rule panel with access to the current native response-editor selection.
     *
     * @param controller workspace controller
     * @param regexSelectionSupplier response selection supplier
     */
    public RuleSummaryPanel(
            FlowWorkspaceController controller,
            Supplier<Optional<RequestResponsePanel.ResponseRegexSelection>> regexSelectionSupplier) {
        this(controller, regexSelectionSupplier, null);
    }

    /** Creates a rule panel with Access Testing variable/profile integration. */
    public RuleSummaryPanel(
            FlowWorkspaceController controller,
            Supplier<Optional<RequestResponsePanel.ResponseRegexSelection>> regexSelectionSupplier,
            AccessUiController accessController) {
        this(controller, regexSelectionSupplier, accessController, Optional::empty);
    }

    /** Creates a rule panel using the currently displayed response for optional extraction discovery. */
    public RuleSummaryPanel(FlowWorkspaceController controller,
            Supplier<Optional<RequestResponsePanel.ResponseRegexSelection>> regexSelectionSupplier,
            AccessUiController accessController,
            Supplier<Optional<burp.api.montoya.http.message.responses.HttpResponse>> responseSupplier) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.regexSelectionSupplier = Objects.requireNonNull(regexSelectionSupplier, "regexSelectionSupplier");
        this.accessController = accessController;
        this.responseSupplier = Objects.requireNonNull(responseSupplier, "responseSupplier");

        extractModel = readOnlyModel(EXTRACT_COLUMNS);
        injectModel  = readOnlyModel(INJECT_COLUMNS);
        extractTable = buildTable(extractModel);
        injectTable  = buildTable(injectModel);

        extractTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelectedExtractRule();
                }
            }
        });

        injectTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelectedInjectRule();
                }
            }
        });

        JPanel extractSection = buildSection("Extract Rules", extractTable,
                this::addExtractRule, this::editSelectedExtractRule, this::removeSelectedExtractRule, this::suggestExtractRules);
        JPanel injectSection = buildSection("Inject Rules", injectTable,
                this::addInjectRule, this::editSelectedInjectRule, this::removeSelectedInjectRule,
                this::suggestInjectRules);

        // Equal preferred widths → resetToPreferredSizes() places divider at available/2.
        // resizeWeight(0.5) keeps it 50/50 when the outer layout redistributes space.
        Dimension equalPref = new Dimension(100, 100);
        extractSection.setPreferredSize(equalPref);
        injectSection.setPreferredSize(equalPref);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, extractSection, injectSection);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerSize(4);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    // -------------------------------------------------------------------------
    // Internal builders
    // -------------------------------------------------------------------------

    private static DefaultTableModel readOnlyModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JTable buildTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setPreferredScrollableViewportSize(new java.awt.Dimension(0, 80));
        return table;
    }

    private static JPanel buildSection(String title, JTable table,
                                       Runnable onAdd, Runnable onEdit, Runnable onRemove,
                                       Runnable onSuggest) {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JLabel titleLabel = new JLabel(title, SwingConstants.LEFT);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 0));

        StyledButton addBtn = StyledButton.primary("+");
        addBtn.setToolTipText("Add rule");
        addBtn.addActionListener(e -> onAdd.run());

        StyledButton editBtn = StyledButton.neutral("Edit");
        editBtn.setToolTipText("Edit selected rule");
        editBtn.addActionListener(e -> onEdit.run());

        StyledButton removeBtn = StyledButton.danger("−");
        removeBtn.setToolTipText("Remove selected rule");
        removeBtn.addActionListener(e -> onRemove.run());

        JPanel buttonBar = new JPanel();
        buttonBar.setLayout(new BoxLayout(buttonBar, BoxLayout.X_AXIS));
        buttonBar.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        buttonBar.add(addBtn);
        buttonBar.add(Box.createHorizontalStrut(4));
        buttonBar.add(editBtn);
        buttonBar.add(Box.createHorizontalStrut(4));
        buttonBar.add(removeBtn);
        if (onSuggest != null) {
            buttonBar.add(Box.createHorizontalStrut(4));
            boolean extract = title.equals("Extract Rules");
            StyledButton suggestBtn = StyledButton.neutral(extract ? "Auto…" : "Suggest");
            suggestBtn.setToolTipText(extract ? "Analyze the displayed response and choose fields to extract; no request is sent"
                    : "Suggest injection rules from extractors in earlier steps");
            suggestBtn.addActionListener(event -> onSuggest.run());
            buttonBar.add(suggestBtn);
        }
        buttonBar.add(Box.createHorizontalGlue());

        section.add(titleLabel, BorderLayout.NORTH);
        section.add(new JScrollPane(table), BorderLayout.CENTER);
        section.add(buttonBar, BorderLayout.SOUTH);
        return section;
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    private void refresh() {
        extractModel.setRowCount(0);
        injectModel.setRowCount(0);

        FlowStep step = controller.selectedStep();
        if (step == null) {
            return;
        }

        for (ExtractRule rule : step.extractRules()) {
            extractModel.addRow(new Object[]{
                    rule.name(),
                    rule.sourceSide(),
                    rule.extractorType(),
                    rule.extractorType() == ExtractRule.ExtractorType.REGEX
                            ? rule.regexSource() + " / group "
                                    + (rule.regexGroupIndex() == null ? "legacy" : rule.regexGroupIndex())
                            : "",
                    rule.sourceSelector(),
                    String.join(" → ", rule.transformChain()),
                    rule.targetVariableName()
            });
        }

        for (InjectRule rule : step.injectRules()) {
            injectModel.addRow(new Object[]{
                    rule.name(),
                    rule.target(),
                    rule.targetSelector(),
                    rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE
                            ? "Interactive input" : rule.valueSourceType() != InjectRule.ValueSourceType.VARIABLE
                            ? "\"" + rule.staticValue() + "\""
                            : "$" + rule.valueSourceKey(),
                    String.join(" → ", rule.transformChain()),
                    rule.writeStrategy()
            });
        }
    }

    // -------------------------------------------------------------------------
    // Extract rule actions
    // -------------------------------------------------------------------------

    private void addExtractRule() {
        FlowStep step = controller.selectedStep();
        if (step == null) return;

        RuleEditorDialog.openForExtract(this, controller, step, null, regexSelectionSupplier.get(), responseSupplier, saved -> {
            FlowStep updated = withExtractRules(step, appendRule(step.extractRules(), saved));
            saveStep(updated);
        });
    }

    private void editSelectedExtractRule() {
        FlowStep step = controller.selectedStep();
        int row = extractTable.getSelectedRow();
        if (step == null || row < 0) return;

        ExtractRule rule = step.extractRules().get(row);
        RuleEditorDialog.openForExtract(this, controller, step, rule, regexSelectionSupplier.get(), responseSupplier, saved -> {
            List<ExtractRule> updated = replaceAt(step.extractRules(), row, saved);
            saveStep(withExtractRules(step, updated));
        });
    }

    private void removeSelectedExtractRule() {
        FlowStep step = controller.selectedStep();
        int row = extractTable.getSelectedRow();
        if (step == null || row < 0) return;

        List<ExtractRule> updated = removeAt(step.extractRules(), row);
        saveStep(withExtractRules(step, updated));
    }

    // -------------------------------------------------------------------------
    // Inject rule actions
    // -------------------------------------------------------------------------

    private void addInjectRule() {
        FlowStep step = controller.selectedStep();
        if (step == null) return;

        RuleEditorDialog.openForInject(this, controller, step, null, accessController, saved -> {
            FlowStep updated = withInjectRules(step, appendRule(step.injectRules(), saved));
            saveStep(updated);
        });
    }

    private void editSelectedInjectRule() {
        FlowStep step = controller.selectedStep();
        int row = injectTable.getSelectedRow();
        if (step == null || row < 0) return;

        InjectRule rule = step.injectRules().get(row);
        RuleEditorDialog.openForInject(this, controller, step, rule, accessController, saved -> {
            List<InjectRule> updated = replaceAt(step.injectRules(), row, saved);
            saveStep(withInjectRules(step, updated));
        });
    }

    private void removeSelectedInjectRule() {
        FlowStep step = controller.selectedStep();
        int row = injectTable.getSelectedRow();
        if (step == null || row < 0) return;

        List<InjectRule> updated = removeAt(step.injectRules(), row);
        saveStep(withInjectRules(step, updated));
    }

    private void suggestInjectRules() {
        FlowDefinition flow = controller.activeFlow();
        FlowStep step = controller.selectedStep();
        if (flow == null || step == null) return;
        List<InjectionSuggestionService.Suggestion> suggestions =
                new InjectionSuggestionService().suggest(flow, step);
        if (suggestions.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No new matching request fields were found. Suggestions require a header, cookie, query, form, or JSON field whose name matches an enabled extractor variable from an earlier step.",
                    "Injection suggestions", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JList<InjectionSuggestionService.Suggestion> list = new JList<>(
                suggestions.toArray(InjectionSuggestionService.Suggestion[]::new));
        list.setCellRenderer((values, value, index, selected, focus) -> {
            JLabel label = new JLabel(value.displayLabel());
            label.setOpaque(true);
            label.setBackground(selected ? values.getSelectionBackground() : values.getBackground());
            label.setForeground(selected ? values.getSelectionForeground() : values.getForeground());
            return label;
        });
        list.setSelectionInterval(0, suggestions.size() - 1);
        int choice = JOptionPane.showConfirmDialog(this, new JScrollPane(list),
                "Review injection suggestions", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION || list.getSelectedValuesList().isEmpty()) return;

        List<InjectRule> rules = new ArrayList<>(step.injectRules());
        list.getSelectedValuesList().forEach(suggestion -> rules.add(suggestion.rule()));
        saveStep(step.withInjectRules(rules));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void saveStep(FlowStep updatedStep) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) return;

        List<FlowStep> newSteps = new ArrayList<>(active.steps());
        for (int i = 0; i < newSteps.size(); i++) {
            if (newSteps.get(i).id().equals(updatedStep.id())) {
                newSteps.set(i, updatedStep);
                break;
            }
        }

        FlowDefinition updated = new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                newSteps, active.rules(), active.aggregation(), active.triggerConfig());
        controller.updateFlow(updated);
        controller.setSelectedStep(updatedStep);
    }

    private static FlowStep withExtractRules(FlowStep step, List<ExtractRule> rules) {
        return step.withExtractRules(rules);
    }

    private static FlowStep withInjectRules(FlowStep step, List<InjectRule> rules) {
        return step.withInjectRules(rules);
    }

    private static <T> List<T> appendRule(List<T> existing, T added) {
        List<T> result = new ArrayList<>(existing);
        result.add(added);
        return result;
    }

    private static <T> List<T> replaceAt(List<T> existing, int index, T replacement) {
        List<T> result = new ArrayList<>(existing);
        result.set(index, replacement);
        return result;
    }

    private static <T> List<T> removeAt(List<T> existing, int index) {
        List<T> result = new ArrayList<>(existing);
        result.remove(index);
        return result;
    }
    private void suggestExtractRules() {
        FlowDefinition flow = controller.activeFlow(); FlowStep step = controller.selectedStep();
        if (flow == null || step == null) return;
        var snapshot = responseSupplier.get();
        if (snapshot.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No response is displayed for this request. Capture or run it first.", "Auto extract", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        var message = snapshot.get();
        ResponseExtractionDialog.open(this, () -> ResponseExtractionDialog.snapshot(message).orElseThrow(), step.extractRules(), selected -> {
            FlowDefinition current = controller.flows().stream().filter(f -> f.id().equals(flow.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("The source flow no longer exists"));
            FlowStep currentStep = current.steps().stream().filter(s -> s.id().equals(step.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("The source request no longer exists"));
            var rules = new dev.nquangit.corestra.http.extract.ResponseExtractionSuggestionService().merge(currentStep.extractRules(), selected);
            FlowStep updated = currentStep.withExtractRules(rules);
            controller.updateFlow(new FlowDefinition(current.id(), current.name(), current.description(), current.enabled(),
                    current.steps().stream().map(s -> s.id().equals(updated.id()) ? updated : s).toList(), current.rules(), current.aggregation(), current.triggerConfig()));
        });
    }
}
