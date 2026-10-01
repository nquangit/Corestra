package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.extract.RegexSuggestionService;
import dev.nquangit.corestra.ui.access.AccessUiController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
     * @param controller the workspace controller used to inspect the active flow
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
        RuleEditorDialog dialog = new RuleEditorDialog(window, "Extract Rule",
                buildExtractForm(step, rule, Optional.empty(), onSave));
        dialog.setVisible(true);
    }

    /** Opens an extract editor that can generate a regex from the response selection. */
    public static void openForExtract(
            Component parent,
            FlowWorkspaceController controller,
            FlowStep step,
            ExtractRule rule,
            Optional<RequestResponsePanel.ResponseRegexSelection> selection,
            Consumer<ExtractRule> onSave) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(onSave, "onSave");
        Window window = findWindow(parent);
        RuleEditorDialog dialog = new RuleEditorDialog(window, "Extract Rule",
                buildExtractForm(step, rule, Objects.requireNonNullElse(selection, Optional.empty()), onSave));
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
        openForInject(parent, controller, step, rule, null, onSave);
    }

    /** Opens an inject editor enriched with Access Testing variable profiles. */
    public static void openForInject(
            Component parent,
            FlowWorkspaceController controller,
            FlowStep step,
            InjectRule rule,
            AccessUiController accessController,
            Consumer<InjectRule> onSave) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(onSave, "onSave");

        Window window = findWindow(parent);
        RuleEditorDialog dialog = new RuleEditorDialog(
                window,
                "Inject Rule",
                buildInjectForm(controller.activeFlow(), step, rule, accessController, onSave));
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

    private static JPanel buildExtractForm(
            FlowStep step,
            ExtractRule existing,
            Optional<RequestResponsePanel.ResponseRegexSelection> selection,
            Consumer<ExtractRule> onSave) {
        JTextField nameField = new JTextField(existing != null ? existing.name() : "New Extract Rule", 24);
        JCheckBox enabledBox = new JCheckBox("Enabled", existing == null || existing.enabled());
        JComboBox<ExtractRule.SourceSide> sourceSideCombo =
                new JComboBox<>(ExtractRule.SourceSide.values());
        JComboBox<ExtractRule.ExtractorType> extractorTypeCombo =
                new JComboBox<>(ExtractRule.ExtractorType.values());
        JTextField selectorField = new JTextField(existing != null ? existing.sourceSelector() : "", 24);
        JTextField variableField = new JTextField(existing != null ? existing.targetVariableName() : "", 24);
        JComboBox<ExtractRule.RegexSource> regexSourceCombo =
                new JComboBox<>(ExtractRule.RegexSource.values());
        JSpinner regexGroupSpinner = new JSpinner(new SpinnerNumberModel(
                explicitRegexGroup(existing), 0, 100, 1));
        JTextArea transformsArea = new JTextArea(
                existing != null ? String.join("\n", existing.transformChain()) : "", 5, 24);
        transformsArea.setLineWrap(false);
        JLabel regexSourceLabel = new JLabel("Regex input:");
        JLabel regexGroupLabel = new JLabel("Capture group:");
        JLabel regexHelpLabel = new JLabel("Java regex; first match. Group 0 = full match.");
        JButton testRegexButton = new JButton("Test regex");
        JButton suggestRegexButton = new JButton("Suggest from response selection");
        suggestRegexButton.setEnabled(selection.isPresent());
        suggestRegexButton.setToolTipText(selection.isPresent()
                ? "Build and verify a regex from the text selected in the response editor"
                : "Select a value in the response editor before opening this dialog");
        JLabel testResultLabel = new JLabel(" ");
        JLabel errorLabel = new JLabel(" ");

        if (existing != null) {
            sourceSideCombo.setSelectedItem(existing.sourceSide());
            extractorTypeCombo.setSelectedItem(existing.extractorType());
            regexSourceCombo.setSelectedItem(existing.regexSource());
        }

        String initialSelector = displayRegexPattern(existing);
        if (existing != null && existing.extractorType() == ExtractRule.ExtractorType.REGEX) {
            selectorField.setText(initialSelector);
        }

        Runnable updateRegexFields = () -> {
            boolean regex = extractorTypeCombo.getSelectedItem() == ExtractRule.ExtractorType.REGEX;
            regexSourceLabel.setVisible(regex);
            regexSourceCombo.setVisible(regex);
            regexGroupLabel.setVisible(regex);
            regexGroupSpinner.setVisible(regex);
            regexHelpLabel.setVisible(regex);
            testRegexButton.setVisible(regex);
            suggestRegexButton.setVisible(regex);
            testResultLabel.setVisible(regex);
        };
        extractorTypeCombo.addActionListener(event -> updateRegexFields.run());

        JPanel form = buildGridForm(new Object[][]{
                {"Name:", nameField},
                {"", enabledBox},
                {"Source side:", sourceSideCombo},
                {"Extractor type:", extractorTypeCombo},
                {"Selector:", selectorField},
                {regexSourceLabel, regexSourceCombo},
                {regexGroupLabel, regexGroupSpinner},
                {"", regexHelpLabel},
                {"", testRegexButton},
                {"", suggestRegexButton},
                {"Test result:", testResultLabel},
                {"Target variable:", variableField},
                {"Transforms (one per line):", new JScrollPane(transformsArea)},
                {"", new JLabel("Examples: trim, substring:0:16, stripPrefix:Bearer, base64Decode")},
                {"", errorLabel}
        });
        updateRegexFields.run();

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        wrapper.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        testRegexButton.addActionListener(event -> {
            try {
                ExtractRule candidate = createExtractRule(
                        existing,
                        nameField.getText(),
                        enabledBox.isSelected(),
                        (ExtractRule.SourceSide) sourceSideCombo.getSelectedItem(),
                        ExtractRule.ExtractorType.REGEX,
                        selectorField.getText(),
                        variableField.getText().isBlank() ? "preview" : variableField.getText(),
                        transformLines(transformsArea.getText()),
                        (ExtractRule.RegexSource) regexSourceCombo.getSelectedItem(),
                        (Integer) regexGroupSpinner.getValue());
                ExtractionResult result = testExtraction(step, candidate);
                testResultLabel.setText(result.isSuccess()
                        ? "Matched: " + abbreviate(result.value())
                        : "No match: " + result.failureReason());
            } catch (IllegalArgumentException exception) {
                testResultLabel.setText("Invalid rule: " + exception.getMessage());
            }
        });

        suggestRegexButton.addActionListener(event -> selection.ifPresent(selected -> {
            RegexSuggestionService.SuggestionResult result = new RegexSuggestionService().suggest(
                    selected.snapshot(), selected.source(), selected.start(), selected.end());
            if (!result.success()) {
                testResultLabel.setText("Suggestion failed: " + result.failureReason());
                return;
            }
            RegexSuggestionService.Suggestion suggestion = result.suggestion();
            extractorTypeCombo.setSelectedItem(ExtractRule.ExtractorType.REGEX);
            sourceSideCombo.setSelectedItem(ExtractRule.SourceSide.RESPONSE);
            regexSourceCombo.setSelectedItem(suggestion.source());
            regexGroupSpinner.setValue(suggestion.captureGroup());
            selectorField.setText(suggestion.regex());
            testResultLabel.setText("Suggested and verified: " + suggestion.matchCount()
                    + " match" + (suggestion.matchCount() == 1 ? "" : "es"));
        }));

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

            ExtractRule saved = createExtractRule(
                    existing,
                    name,
                    enabledBox.isSelected(),
                    (ExtractRule.SourceSide) sourceSideCombo.getSelectedItem(),
                    type,
                    selector,
                    variable,
                    transformLines(transformsArea.getText()),
                    (ExtractRule.RegexSource) regexSourceCombo.getSelectedItem(),
                    (Integer) regexGroupSpinner.getValue());
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

    private static JPanel buildInjectForm(
            FlowDefinition flow,
            FlowStep step,
            InjectRule existing,
            AccessUiController accessController,
            Consumer<InjectRule> onSave) {
        JTextField nameField = new JTextField(existing != null ? existing.name() : "New Inject Rule", 24);
        JCheckBox enabledBox = new JCheckBox("Enabled", existing == null || existing.enabled());
        JComboBox<InjectRule.Target> targetCombo = new JComboBox<>(InjectRule.Target.values());
        JTextField selectorField = new JTextField(existing != null ? existing.targetSelector() : "", 24);
        JComboBox<InjectRule.ValueSourceType> sourceTypeCombo =
                new JComboBox<>(InjectRule.ValueSourceType.values());
        InjectionVariableCatalog variableCatalog = new InjectionVariableCatalog(accessController);
        List<InjectionVariableCatalog.VariableOption> variableOptions = variableCatalog.options(flow, step);
        JComboBox<InjectionVariableCatalog.VariableOption> sourceKeyCombo = new JComboBox<>(
                variableOptions.toArray(InjectionVariableCatalog.VariableOption[]::new));
        sourceKeyCombo.setEditable(true);
        sourceKeyCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof InjectionVariableCatalog.VariableOption option) {
                    label.setText(option.displayLabel());
                }
                return label;
            }
        });
        sourceKeyCombo.setPrototypeDisplayValue(new InjectionVariableCatalog.VariableOption(
                "credentials.username", "Credentials", "Auth profile name", "", true));
        selectExistingVariable(sourceKeyCombo, existing == null ? "" : existing.valueSourceKey());
        JTextField staticValueField = new JTextField(existing != null ? existing.staticValue() : "", 24);
        JTextArea transformsArea = new JTextArea(
                existing != null ? String.join("\n", existing.transformChain()) : "", 5, 24);
        JComboBox<InjectRule.WriteStrategy> strategyCombo =
                new JComboBox<>(InjectRule.WriteStrategy.values());
        JLabel sourceKeyLabel = new JLabel("Variable key:");
        JLabel staticValueLabel = new JLabel("Static value:");
        JLabel variableHelpLabel = new JLabel(variableOptions.isEmpty()
                ? "No known variables yet; enter a custom runtime key."
                : "Choose a known key or type a custom key. The flow stores the key, not a profile ID.");
        JLabel previewLabel = new JLabel(" ");
        JButton saveToProfileButton = new JButton("Save value to profile…");
        saveToProfileButton.setToolTipText(
                "Save a credentials.* or data.* value to an Access Testing profile after confirmation");
        saveToProfileButton.setEnabled(false);
        JLabel errorLabel = new JLabel(" ");

        if (existing != null) {
            targetCombo.setSelectedItem(existing.target());
            sourceTypeCombo.setSelectedItem(existing.valueSourceType());
            strategyCombo.setSelectedItem(existing.writeStrategy());
        }

        Runnable updateSourceFields = () -> {
            boolean isStatic = sourceTypeCombo.getSelectedItem() == InjectRule.ValueSourceType.STATIC;
            sourceKeyLabel.setVisible(!isStatic);
            sourceKeyCombo.setVisible(!isStatic);
            variableHelpLabel.setVisible(!isStatic);
            previewLabel.setVisible(!isStatic);
            saveToProfileButton.setVisible(!isStatic && accessController != null);
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
                {sourceKeyLabel, sourceKeyCombo},
                {"", variableHelpLabel},
                {"Preview:", previewLabel},
                {"", saveToProfileButton},
                {staticValueLabel, staticValueField},
                {"Write strategy:", strategyCombo},
                {"Transforms (one per line):", new JScrollPane(transformsArea)},
                {"", new JLabel("Applied to the resolved value before injection")},
                {"", errorLabel}
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        wrapper.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        Runnable updateVariablePreview = () -> {
            Object selected = sourceKeyCombo.getEditor().getItem();
            if (selected instanceof InjectionVariableCatalog.VariableOption option) {
                previewLabel.setText(option.displayPreview());
            } else {
                String key = selectedVariableKey(sourceKeyCombo);
                previewLabel.setText(key.isEmpty()
                        ? "Enter a variable key"
                        : contextHint(key));
            }
            String key = selectedVariableKey(sourceKeyCombo);
            saveToProfileButton.setEnabled(
                    key.startsWith("credentials.") || key.startsWith("data."));
        };
        sourceKeyCombo.addActionListener(event -> updateVariablePreview.run());
        if (sourceKeyCombo.getEditor().getEditorComponent() instanceof JTextField editor) {
            editor.getDocument().addDocumentListener(onDocumentChange(updateVariablePreview));
        }
        saveToProfileButton.addActionListener(event -> {
            String key = selectedVariableKey(sourceKeyCombo);
            try {
                saveVariableToProfile(wrapper, accessController, key);
                reloadVariableOptions(sourceKeyCombo, variableCatalog.options(flow, step), key);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                JOptionPane.showMessageDialog(wrapper, exception.getMessage(),
                        "Cannot save profile value", JOptionPane.ERROR_MESSAGE);
            }
        });
        updateVariablePreview.run();

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
            String sourceKey = isStatic ? "" : selectedVariableKey(sourceKeyCombo);
            String staticVal = isStatic ? staticValueField.getText() : "";

            if (!isStatic && sourceKey.isEmpty()) {
                errorLabel.setText("Variable key must not be empty.");
                return;
            }
            if (!isStatic && sourceKey.chars().anyMatch(Character::isWhitespace)) {
                errorLabel.setText("Variable key must not contain whitespace.");
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
                    (InjectRule.WriteStrategy) strategyCombo.getSelectedItem(),
                    transformLines(transformsArea.getText()));
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

    static List<ExtractedVariableOption> availableExtractedVariables(
            FlowDefinition flow,
            FlowStep currentStep) {
        if (flow == null || currentStep == null) {
            return List.of();
        }
        Map<String, ExtractedVariableOption> byKey = new LinkedHashMap<>();
        boolean currentStepFound = false;
        for (FlowStep candidate : flow.steps()) {
            if (candidate.id().equals(currentStep.id())) {
                currentStepFound = true;
                break;
            }
            if (!candidate.enabled()) {
                continue;
            }
            for (ExtractRule rule : candidate.extractRules()) {
                if (!rule.enabled()) {
                    continue;
                }
                ExtractedVariableOption option = new ExtractedVariableOption(
                        rule.targetVariableName(), candidate.name(), rule.name());
                // A later extractor with the same key is the value visible to this step.
                byKey.remove(option.key());
                byKey.put(option.key(), option);
            }
        }
        return currentStepFound ? List.copyOf(byKey.values()) : List.of();
    }

    private static void selectExistingVariable(
            JComboBox<InjectionVariableCatalog.VariableOption> combo,
            String existingKey) {
        if (existingKey == null || existingKey.isBlank()) {
            if (combo.getItemCount() > 0) {
                combo.setSelectedIndex(0);
            }
            return;
        }
        String normalized = existingKey.strip();
        for (int index = 0; index < combo.getItemCount(); index++) {
            InjectionVariableCatalog.VariableOption option = combo.getItemAt(index);
            if (option.key().equals(normalized)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        combo.getEditor().setItem(normalized);
    }

    static String selectedVariableKey(JComboBox<?> combo) {
        Object selected = combo.getEditor().getItem();
        if (selected instanceof InjectionVariableCatalog.VariableOption option) {
            return option.key();
        }
        if (selected instanceof ExtractedVariableOption option) {
            return option.key();
        }
        return selected == null ? "" : selected.toString().strip();
    }

    private static String contextHint(String key) {
        if (key.startsWith("credentials.") || key.startsWith("auth.")) {
            return "Resolved when this run has an Auth Profile.";
        }
        if (key.startsWith("data.")) {
            return "Resolved when this run has a Data Profile.";
        }
        if (key.startsWith("env.")) {
            return "Add this key in Flowise Run Context or the Access environment.";
        }
        return "Custom key; provide it in Run Context or extract it in an earlier step.";
    }

    private static void reloadVariableOptions(
            JComboBox<InjectionVariableCatalog.VariableOption> combo,
            List<InjectionVariableCatalog.VariableOption> options,
            String selectedKey) {
        combo.removeAllItems();
        options.forEach(combo::addItem);
        selectExistingVariable(combo, selectedKey);
    }

    private static void saveVariableToProfile(
            Component parent,
            AccessUiController accessController,
            String scopedKey) {
        if (accessController == null) {
            throw new IllegalStateException("Access Testing is not available.");
        }
        boolean credential = scopedKey != null && scopedKey.startsWith("credentials.");
        boolean data = scopedKey != null && scopedKey.startsWith("data.");
        if (!credential && !data) {
            throw new IllegalArgumentException(
                    "Only credentials.* and data.* keys can be saved to a profile.");
        }

        JComboBox<ProfileChoice> profileCombo = new JComboBox<>();
        if (credential) {
            for (AuthProfile profile : accessController.authProfiles()) {
                profileCombo.addItem(new ProfileChoice(profile.id(), profile.name(), false));
            }
        } else {
            for (DataProfile profile : accessController.dataProfiles()) {
                profileCombo.addItem(new ProfileChoice(profile.id(), profile.name(), false));
            }
        }
        ProfileChoice createChoice = new ProfileChoice("", "Create new profile…", true);
        profileCombo.addItem(createChoice);
        if (profileCombo.getItemCount() == 1) {
            profileCombo.setSelectedItem(createChoice);
        }

        JTextField nameField = new JTextField(credential ? "New Auth Profile" : "New Data Profile");
        JTextField roleField = new JTextField();
        JComboBox<ProfileChoice> relationCombo = new JComboBox<>();
        if (credential) {
            relationCombo.addItem(new ProfileChoice(AuthProfile.MANUAL_PROVIDER_ID, "Manual", false));
            for (AuthProvider provider : accessController.authProviders()) {
                relationCombo.addItem(new ProfileChoice(provider.id(), provider.name(), false));
            }
        } else {
            relationCombo.addItem(new ProfileChoice("", "No owner", false));
            for (AuthProfile profile : accessController.authProfiles()) {
                relationCombo.addItem(new ProfileChoice(profile.id(), profile.name(), false));
            }
        }
        JTextField valueField = credential ? new JPasswordField(24) : new JTextField(24);
        JLabel keyLabel = new JLabel(scopedKey);
        JLabel effectLabel = new JLabel("Existing values with this key will be replaced after confirmation.");

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("Profile:"));
        fields.add(profileCombo);
        fields.add(new JLabel("Variable key:"));
        fields.add(keyLabel);
        fields.add(new JLabel("Value:"));
        fields.add(valueField);
        fields.add(new JLabel("New profile name:"));
        fields.add(nameField);
        if (credential) {
            fields.add(new JLabel("Role:"));
            fields.add(roleField);
            fields.add(new JLabel("Auth provider:"));
        } else {
            fields.add(new JLabel("Owner Auth Profile:"));
        }
        fields.add(relationCombo);

        Runnable updateNewFields = () -> {
            ProfileChoice selected = (ProfileChoice) profileCombo.getSelectedItem();
            boolean creating = selected != null && selected.createNew();
            nameField.setEnabled(creating);
            roleField.setEnabled(creating);
            relationCombo.setEnabled(creating);
        };
        profileCombo.addActionListener(event -> updateNewFields.run());
        updateNewFields.run();

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(fields, BorderLayout.CENTER);
        content.add(effectLabel, BorderLayout.SOUTH);
        int result = JOptionPane.showConfirmDialog(
                parent,
                content,
                credential ? "Save Credential Value" : "Save Data Value",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        ProfileChoice profile = (ProfileChoice) profileCombo.getSelectedItem();
        ProfileChoice relation = (ProfileChoice) relationCombo.getSelectedItem();
        if (profile == null) {
            throw new IllegalArgumentException("Select a profile.");
        }
        String value = valueField.getText();
        if (credential) {
            accessController.saveCredentialValue(
                    profile.id(), nameField.getText(), roleField.getText(),
                    relation == null ? AuthProfile.MANUAL_PROVIDER_ID : relation.id(), scopedKey, value);
        } else {
            accessController.saveDataValue(
                    profile.id(), nameField.getText(), relation == null ? "" : relation.id(), scopedKey, value);
        }
        JOptionPane.showMessageDialog(
                parent,
                "Saved " + scopedKey + " to " + (profile.createNew() ? nameField.getText().strip() : profile.label()) + ".",
                "Profile updated",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static DocumentListener onDocumentChange(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        };
    }

    private record ProfileChoice(String id, String label, boolean createNew) {
        @Override
        public String toString() {
            return label;
        }
    }

    record ExtractedVariableOption(String key, String stepName, String ruleName) {
        ExtractedVariableOption {
            key = Objects.requireNonNullElse(key, "").strip();
            stepName = Objects.requireNonNullElse(stepName, "").strip();
            ruleName = Objects.requireNonNullElse(ruleName, "").strip();
        }

        @Override
        public String toString() {
            return key;
        }

        String displayLabel() {
            return key + "  —  " + stepName + " / " + ruleName;
        }
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

    private static ExtractRule createExtractRule(
            ExtractRule existing,
            String name,
            boolean enabled,
            ExtractRule.SourceSide sourceSide,
            ExtractRule.ExtractorType type,
            String selector,
            String variable,
            List<String> transforms,
            ExtractRule.RegexSource regexSource,
            int regexGroup) {
        return new ExtractRule(
                existing != null ? existing.id() : UUID.randomUUID().toString(),
                name.strip().isEmpty() ? "Regex preview" : name.strip(),
                enabled,
                sourceSide,
                type,
                selector.strip(),
                variable.strip(),
                transforms,
                regexSource,
                type == ExtractRule.ExtractorType.REGEX ? regexGroup : null);
    }

    private static ExtractionResult testExtraction(FlowStep step, ExtractRule rule) {
        HttpMessageSnapshot request = new HttpMessageSnapshot(
                step.requestTemplate().method() + " " + step.requestTemplate().pathAndQuery() + " HTTP/1.1",
                step.requestTemplate().headerLines(),
                step.requestTemplate().body());
        HttpMessageSnapshot response = decodeResponseSnapshot(step.lastResponseRaw());
        return new ExtractionService().extract(rule, request, response);
    }

    private static HttpMessageSnapshot decodeResponseSnapshot(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return parseRawMessage(new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static HttpMessageSnapshot parseRawMessage(String raw) {
        int separator = raw.indexOf("\r\n\r\n");
        int separatorLength = 4;
        if (separator < 0) {
            separator = raw.indexOf("\n\n");
            separatorLength = 2;
        }
        String head = separator < 0 ? raw : raw.substring(0, separator);
        String body = separator < 0 ? "" : raw.substring(separator + separatorLength);
        String[] lines = head.split("\\r?\\n", -1);
        List<String> headers = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                headers.add(lines[index]);
            }
        }
        return new HttpMessageSnapshot(lines.length == 0 ? "" : lines[0], headers, body);
    }

    private static List<String> transformLines(String text) {
        return text == null ? List.of() : text.lines().map(String::stripLeading)
                .filter(line -> !line.isEmpty()).toList();
    }

    private static int explicitRegexGroup(ExtractRule rule) {
        if (rule == null || rule.regexGroupIndex() == null) {
            return legacyRegexGroup(rule == null ? "" : rule.sourceSelector());
        }
        return rule.regexGroupIndex();
    }

    private static String displayRegexPattern(ExtractRule rule) {
        if (rule == null || rule.regexGroupIndex() != null) {
            return rule == null ? "" : rule.sourceSelector();
        }
        String selector = rule.sourceSelector();
        int separator = selector.lastIndexOf('|');
        if (separator >= 0 && legacyRegexGroup(selector) >= 0) {
            String suffix = selector.substring(separator + 1).strip();
            if (suffix.chars().allMatch(Character::isDigit)) {
                return selector.substring(0, separator);
            }
        }
        return selector;
    }

    private static int legacyRegexGroup(String selector) {
        int separator = selector == null ? -1 : selector.lastIndexOf('|');
        if (separator < 0 || separator == selector.length() - 1) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(selector.substring(separator + 1).strip()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String abbreviate(String value) {
        String normalized = value == null ? "" : value.replace("\r", "\\r").replace("\n", "\\n");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
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
