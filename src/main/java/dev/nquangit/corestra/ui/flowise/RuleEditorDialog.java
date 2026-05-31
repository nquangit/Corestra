package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A modal dialog for creating or editing an {@link ExtractRule} or {@link InjectRule}.
 *
 * <p>Use the static factory methods to open the dialog:
 * <ul>
 *   <li>{@link #openForExtract(Component, FlowWorkspaceController, FlowStep, ExtractRule, Consumer)}</li>
 *   <li>{@link #openForInject(Component, FlowWorkspaceController, FlowStep, InjectRule, Consumer)}</li>
 * </ul>
 *
 * <p>When the user presses OK and the form is valid the supplied callback is invoked with the
 * constructed rule. When the user presses Cancel the dialog closes with no side effects.</p>
 */
public final class RuleEditorDialog extends JDialog {

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /**
     * Opens a modal dialog for creating or editing an {@link ExtractRule}.
     *
     * @param parent     the parent component used to position the dialog
     * @param controller the workspace controller (used to resolve parent window)
     * @param step       the step being edited
     * @param rule       an existing rule to edit, or {@code null} to create a new one
     * @param onSave     callback invoked with the saved rule when the user presses OK
     */
    public static void openForExtract(
            Component parent,
            FlowWorkspaceController controller,
            FlowStep step,
            ExtractRule rule,
            Consumer<ExtractRule> onSave) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(onSave, "onSave");

        Window window = findWindow(parent);
        RuleEditorDialog dialog = new RuleEditorDialog(window, "Extract Rule", buildExtractForm(rule, onSave));
        dialog.setVisible(true);
    }

    /**
     * Opens a modal dialog for creating or editing an {@link InjectRule}.
     *
     * @param parent     the parent component used to position the dialog
     * @param controller the workspace controller (used to resolve parent window)
     * @param step       the step being edited
     * @param rule       an existing rule to edit, or {@code null} to create a new one
     * @param onSave     callback invoked with the saved rule when the user presses OK
     */
    public static void openForInject(
            Component parent,
            FlowWorkspaceController controller,
            FlowStep step,
            InjectRule rule,
            Consumer<InjectRule> onSave) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(onSave, "onSave");

        Window window = findWindow(parent);
        RuleEditorDialog dialog = new RuleEditorDialog(window, "Inject Rule", buildInjectForm(rule, onSave));
        dialog.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    private RuleEditorDialog(Window owner, String title, JPanel formContent) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout());
        add(formContent, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(owner);
        setResizable(true);
    }

    // -------------------------------------------------------------------------
    // Form builders
    // -------------------------------------------------------------------------

    private static JPanel buildExtractForm(ExtractRule existing, Consumer<ExtractRule> onSave) {
        JTextField nameField = new JTextField(existing != null ? existing.name() : "New Extract Rule", 24);
        JCheckBox enabledBox = new JCheckBox("Enabled", existing == null || existing.enabled());
        JComboBox<ExtractRule.SourceSide> sourceSideCombo =
                new JComboBox<>(ExtractRule.SourceSide.values());
        JComboBox<ExtractRule.ExtractorType> extractorTypeCombo =
                new JComboBox<>(ExtractRule.ExtractorType.values());
        JTextField selectorField = new JTextField(existing != null ? existing.sourceSelector() : "", 24);
        JTextField variableField = new JTextField(existing != null ? existing.targetVariableName() : "", 24);
        JLabel errorLabel = new JLabel(" ");

        if (existing != null) {
            sourceSideCombo.setSelectedItem(existing.sourceSide());
            extractorTypeCombo.setSelectedItem(existing.extractorType());
        }

        JPanel form = buildGridForm(new Object[][]{
                {"Name:", nameField},
                {"", enabledBox},
                {"Source side:", sourceSideCombo},
                {"Extractor type:", extractorTypeCombo},
                {"Selector:", selectorField},
                {"Target variable:", variableField},
                {"", errorLabel}
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        wrapper.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(e -> {
            String name = nameField.getText().strip();
            String selector = selectorField.getText().strip();
            String variable = variableField.getText().strip();

            if (name.isEmpty()) {
                errorLabel.setText("Name must not be empty.");
                return;
            }
            if (variable.isEmpty()) {
                errorLabel.setText("Target variable must not be empty.");
                return;
            }

            ExtractRule.ExtractorType type = (ExtractRule.ExtractorType) extractorTypeCombo.getSelectedItem();
            boolean needsSelector = type == ExtractRule.ExtractorType.HEADER
                    || type == ExtractRule.ExtractorType.COOKIE
                    || type == ExtractRule.ExtractorType.JSON_PATH
                    || type == ExtractRule.ExtractorType.REGEX;
            if (needsSelector && selector.isEmpty()) {
                errorLabel.setText("Selector required for " + type + ".");
                return;
            }

            String id = existing != null ? existing.id() : UUID.randomUUID().toString();
            ExtractRule saved = new ExtractRule(
                    id,
                    name,
                    enabledBox.isSelected(),
                    (ExtractRule.SourceSide) sourceSideCombo.getSelectedItem(),
                    type,
                    selector,
                    variable,
                    existing != null ? existing.transformChain() : List.of());
            onSave.accept(saved);
            closeDialog(okButton);
        });

        cancelButton.addActionListener(e -> closeDialog(cancelButton));

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonBar.add(okButton);
        buttonBar.add(cancelButton);
        wrapper.add(buttonBar, BorderLayout.SOUTH);
        return wrapper;
    }

    private static JPanel buildInjectForm(InjectRule existing, Consumer<InjectRule> onSave) {
        JTextField nameField = new JTextField(existing != null ? existing.name() : "New Inject Rule", 24);
        JCheckBox enabledBox = new JCheckBox("Enabled", existing == null || existing.enabled());
        JComboBox<InjectRule.Target> targetCombo = new JComboBox<>(InjectRule.Target.values());
        JTextField selectorField = new JTextField(existing != null ? existing.targetSelector() : "", 24);
        JComboBox<InjectRule.ValueSourceType> sourceTypeCombo =
                new JComboBox<>(InjectRule.ValueSourceType.values());
        JTextField sourceKeyField = new JTextField(existing != null ? existing.valueSourceKey() : "", 24);
        JTextField staticValueField = new JTextField(existing != null ? existing.staticValue() : "", 24);
        JComboBox<InjectRule.WriteStrategy> strategyCombo =
                new JComboBox<>(InjectRule.WriteStrategy.values());
        JLabel sourceKeyLabel = new JLabel("Variable key:");
        JLabel staticValueLabel = new JLabel("Static value:");
        JLabel errorLabel = new JLabel(" ");

        if (existing != null) {
            targetCombo.setSelectedItem(existing.target());
            sourceTypeCombo.setSelectedItem(existing.valueSourceType());
            strategyCombo.setSelectedItem(existing.writeStrategy());
        }

        Runnable updateSourceFields = () -> {
            boolean isStatic = sourceTypeCombo.getSelectedItem() == InjectRule.ValueSourceType.STATIC;
            sourceKeyLabel.setVisible(!isStatic);
            sourceKeyField.setVisible(!isStatic);
            staticValueLabel.setVisible(isStatic);
            staticValueField.setVisible(isStatic);
        };
        sourceTypeCombo.addActionListener(e -> updateSourceFields.run());
        updateSourceFields.run();

        JPanel form = buildGridForm(new Object[][]{
                {"Name:", nameField},
                {"", enabledBox},
                {"Target:", targetCombo},
                {"Selector:", selectorField},
                {"Value source:", sourceTypeCombo},
                {sourceKeyLabel, sourceKeyField},
                {staticValueLabel, staticValueField},
                {"Write strategy:", strategyCombo},
                {"", errorLabel}
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        wrapper.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(e -> {
            String name = nameField.getText().strip();
            if (name.isEmpty()) {
                errorLabel.setText("Name must not be empty.");
                return;
            }

            InjectRule.Target target = (InjectRule.Target) targetCombo.getSelectedItem();
            String selector = selectorField.getText().strip();
            InjectRule.ValueSourceType srcType = (InjectRule.ValueSourceType) sourceTypeCombo.getSelectedItem();

            boolean needsSelector = target != InjectRule.Target.REQUEST_RAW_BODY_APPEND
                    && target != InjectRule.Target.REQUEST_RAW_BODY_REPLACE;
            if (needsSelector && selector.isEmpty()) {
                errorLabel.setText("Selector required for " + target + ".");
                return;
            }

            boolean isStatic = srcType == InjectRule.ValueSourceType.STATIC;
            String sourceKey = isStatic ? "" : sourceKeyField.getText().strip();
            String staticVal = isStatic ? staticValueField.getText() : "";

            if (!isStatic && sourceKey.isEmpty()) {
                errorLabel.setText("Variable key must not be empty.");
                return;
            }

            String id = existing != null ? existing.id() : UUID.randomUUID().toString();
            InjectRule saved = new InjectRule(
                    id,
                    name,
                    enabledBox.isSelected(),
                    target,
                    selector,
                    srcType,
                    sourceKey,
                    staticVal,
                    (InjectRule.WriteStrategy) strategyCombo.getSelectedItem());
            onSave.accept(saved);
            closeDialog(okButton);
        });

        cancelButton.addActionListener(e -> closeDialog(cancelButton));

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonBar.add(okButton);
        buttonBar.add(cancelButton);
        wrapper.add(buttonBar, BorderLayout.SOUTH);
        return wrapper;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JPanel buildGridForm(Object[][] rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(2, 0, 2, 8);
        lc.gridx = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(2, 0, 2, 0);
        fc.gridx = 1;

        for (int i = 0; i < rows.length; i++) {
            lc.gridy = i;
            fc.gridy = i;
            Object labelObj = rows[i][0];
            Object fieldObj = rows[i][1];

            if (labelObj instanceof Component c) {
                panel.add(c, lc);
            } else {
                panel.add(new JLabel(labelObj.toString()), lc);
            }

            if (fieldObj instanceof Component c) {
                panel.add(c, fc);
            } else {
                panel.add(new JLabel(fieldObj.toString()), fc);
            }
        }
        return panel;
    }

    private static void closeDialog(Component c) {
        Window w = findWindow(c);
        if (w != null) {
            w.dispose();
        }
    }

    private static Window findWindow(Component c) {
        if (c == null) {
            return null;
        }
        Component cur = c;
        while (cur != null) {
            if (cur instanceof Window w) {
                return w;
            }
            cur = cur.getParent();
        }
        return null;
    }
}
