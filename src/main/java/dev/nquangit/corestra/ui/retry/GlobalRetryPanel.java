package dev.nquangit.corestra.ui.retry;

import dev.nquangit.corestra.flow.control.StepConditionOperator;
import dev.nquangit.corestra.http.retry.RetryCondition;
import dev.nquangit.corestra.http.retry.RetryConditionSource;
import dev.nquangit.corestra.http.retry.RetryConfig;
import dev.nquangit.corestra.http.retry.RetryMessage;
import dev.nquangit.corestra.http.retry.RetryPresets;
import dev.nquangit.corestra.http.retry.RetryProfile;
import dev.nquangit.corestra.http.retry.RetryRequestContext;
import dev.nquangit.corestra.http.retry.RetryRule;
import dev.nquangit.corestra.http.retry.RetryRuleTester;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.UiTheme;
import dev.nquangit.corestra.ui.component.WrappedText;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Root "Global Retry" tab. Edits ordered retry profiles (which requests they cover and how long to
 * wait), each with ordered rules (first match decides RETRY or STOP) whose conditions are grouped:
 * conditions in the same group are AND-ed, groups are OR-ed. Changes stay in the form until Save.
 * All guidance is plain text; Burp's look-and-feel does not render HTML labels reliably.
 */
public final class GlobalRetryPanel extends JPanel {
    private final ProjectSettingsRepository repository;
    private final RetryRuleTester tester = new RetryRuleTester(Clock.systemUTC());

    // Working copy
    private final List<RetryProfile> profiles = new ArrayList<>();
    private final List<RetryRule> rules = new ArrayList<>();
    private int profileIndex = -1;
    private int ruleIndex = -1;
    private boolean updating;

    // Top bar
    private final JCheckBox masterEnabled = new JCheckBox("Enable global retry");
    private final JLabel status = new JLabel(" ");

    // Profiles
    private final DefaultListModel<String> profileListModel = new DefaultListModel<>();
    private final JList<String> profileList = new JList<>(profileListModel);

    // Profile form
    private final JTextField profileName = new JTextField(24);
    private final JCheckBox profileEnabled = new JCheckBox("Profile enabled");
    private final Map<String, JCheckBox> tools = new LinkedHashMap<>();
    private final JCheckBox scopeOnly = new JCheckBox("Only requests in Burp's target scope");
    private final JTextField hostPattern = new JTextField(28);
    private final JTextField pathPattern = new JTextField(28);
    private final JTextField methods = new JTextField(28);
    private final JSpinner maxRetries = new JSpinner(new SpinnerNumberModel(3, 1, RetryProfile.MAX_RETRIES, 1));
    private final JSpinner baseDelay = new JSpinner(new SpinnerNumberModel(1_000L, 0L, RetryProfile.MAX_DELAY_MILLIS, 100L));
    private final JSpinner maxDelay = new JSpinner(new SpinnerNumberModel(30_000L, 0L, RetryProfile.MAX_DELAY_MILLIS, 1_000L));
    private final JSpinner multiplier = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 10.0, 0.5));
    private final JCheckBox retryAfter = new JCheckBox("Honor Retry-After header (when the rule has no fixed delay)");
    private final JSpinner budget = new JSpinner(new SpinnerNumberModel(60, 1, (int) (RetryProfile.MAX_DELAY_MILLIS / 1000), 5));
    private final JCheckBox cooldown = new JCheckBox("Host cooldown: hold other requests to a host while it is being retried");

    // Rules
    private final RuleTableModel ruleTableModel = new RuleTableModel();
    private final JTable ruleTable = new JTable(ruleTableModel);
    private final JComboBox<RetryPresets.Preset> presetCombo =
            new JComboBox<>(RetryPresets.all().toArray(RetryPresets.Preset[]::new));
    private final WrappedText presetDescription = new WrappedText(" ", 640);

    // Rule form
    private final JTextField ruleName = new JTextField(24);
    private final JCheckBox ruleEnabled = new JCheckBox("Rule enabled");
    private final JComboBox<RetryRule.Action> ruleAction = new JComboBox<>(RetryRule.Action.values());
    private final JTextField ruleDelay = new JTextField(8);
    private final JTextField ruleMaxRetries = new JTextField(4);
    private final JComboBox<RetryRule.Highlight> ruleHighlight = new JComboBox<>(RetryRule.Highlight.values());
    private final ConditionTableModel conditionModel = new ConditionTableModel();
    private final JTable conditionTable = new JTable(conditionModel);
    private final WrappedText expression = new WrappedText(" ", 640);
    private final JPanel ruleEditor = new JPanel(new BorderLayout(0, 6));

    // Test
    private final JComboBox<String> testTool = new JComboBox<>(RetryProfile.SUPPORTED_TOOLS.toArray(String[]::new));
    private final JTextField testMethod = new JTextField("GET", 6);
    private final JTextField testHost = new JTextField("example.com", 18);
    private final JTextField testPath = new JTextField("/", 18);
    private final JCheckBox testInScope = new JCheckBox("In Burp scope", true);
    private final JTextArea testResponse = new JTextArea(12, 60);
    private final WrappedText testResult = new WrappedText(" ", 640);

    /**
     * Creates the tab.
     *
     * @param repository project settings store
     */
    public GlobalRetryPanel(ProjectSettingsRepository repository) {
        super(new BorderLayout(0, 6));
        this.repository = Objects.requireNonNull(repository, "repository");
        for (String tool : RetryProfile.SUPPORTED_TOOLS) {
            JCheckBox box = new JCheckBox(tool);
            if (tool.equals("SCANNER")) {
                box.setToolTipText("Retry waits add to the measured response time and can cause false positives"
                        + " in time-based checks (e.g. blind SQLi).");
            }
            tools.put(tool, box);
        }
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(topBar(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, profilesPanel(), profileTabs());
        split.setResizeWeight(0.0);
        split.setDividerLocation(230);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        load(repository.load().retry());
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && storedMatchesLoaded()) {
                load(repository.load().retry());
            }
        });
    }

    private RetryConfig loaded;

    /** Reloads only when the form has no unsaved edits, so switching tabs never discards work. */
    private boolean storedMatchesLoaded() {
        try {
            return Objects.equals(currentConfig(), loaded);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private JPanel topBar() {
        JButton save = StyledButton.success("Save");
        JButton revert = StyledButton.neutral("Revert");
        JButton defaults = StyledButton.neutral("Load defaults");
        save.addActionListener(event -> save());
        revert.addActionListener(event -> {
            load(repository.load().retry());
            showStatus("Reverted to the saved configuration.", false);
        });
        defaults.addActionListener(event -> {
            load(RetryConfig.defaults());
            showStatus("Defaults loaded. Press Save to keep them.", false);
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(masterEnabled);
        row.add(Box.createHorizontalStrut(8));
        row.add(save);
        row.add(revert);
        row.add(defaults);
        row.add(Box.createHorizontalStrut(10));
        row.add(status);
        JPanel bar = new JPanel(new BorderLayout(0, 6));
        bar.add(row, BorderLayout.NORTH);
        bar.add(new WrappedText("Profiles are tried top to bottom; the first enabled profile that covers a request"
                + " (tool, scope, host, path, method) handles it. Its rules are tried top to bottom on every response:"
                + " the first match decides RETRY (wait, resend, check again) or STOP (deliver as-is, e.g. a permanent"
                + " block). No match means the response is delivered unchanged. Put host-specific profiles and"
                + " specific rules above general ones.", 900), BorderLayout.CENTER);
        return bar;
    }

    private JPanel profilesPanel() {
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) selectProfile(profileList.getSelectedIndex());
        });
        JButton add = StyledButton.primary("Add");
        JButton duplicate = StyledButton.neutral("Duplicate");
        JButton remove = StyledButton.danger("Remove");
        JButton up = StyledButton.neutral("Up");
        JButton down = StyledButton.neutral("Down");
        add.addActionListener(event -> mutateProfiles(() -> {
            RetryProfile profile = RetryProfile.standard("Profile " + (profiles.size() + 1));
            profiles.add(profile);
            return profiles.size() - 1;
        }));
        duplicate.addActionListener(event -> mutateProfiles(() -> {
            if (profileIndex < 0) return profileIndex;
            RetryProfile source = profiles.get(profileIndex);
            profiles.add(profileIndex + 1, source.copyNamed(source.name() + " copy"));
            return profileIndex + 1;
        }));
        remove.addActionListener(event -> mutateProfiles(() -> {
            if (profileIndex < 0) return profileIndex;
            profiles.remove(profileIndex);
            return Math.min(profileIndex, profiles.size() - 1);
        }));
        up.addActionListener(event -> mutateProfiles(() -> move(profiles, profileIndex, -1)));
        down.addActionListener(event -> mutateProfiles(() -> move(profiles, profileIndex, 1)));
        JPanel buttons = new JPanel(new GridLayout(0, 2, 4, 4));
        buttons.add(add); buttons.add(duplicate); buttons.add(up); buttons.add(down); buttons.add(remove);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Profiles (first match wins)"));
        panel.add(new JScrollPane(profileList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setMinimumSize(new Dimension(180, 100));
        return panel;
    }

    private JTabbedPane profileTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Rules", rulesTab());
        tabs.addTab("Scope & limits", scrolled(scopeTab()));
        tabs.addTab("Test", testTab());
        tabs.addChangeListener(event -> {
            if (!updating) tryCommit();
        });
        return tabs;
    }

    private JPanel scopeTab() {
        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.values().forEach(toolRow::add);
        Form coverage = new Form();
        coverage.row("Name:", profileName);
        coverage.span(profileEnabled);
        coverage.row("Tools:", toolRow);
        coverage.span(scopeOnly);
        coverage.row("Host regex (full match, blank = any):", hostPattern);
        coverage.row("Path regex (searched, blank = any):", pathPattern);
        coverage.row("Methods (blank = any):", methods);
        Form limits = new Form();
        limits.row("Max retries:", maxRetries);
        limits.row("Base delay (ms):", baseDelay);
        limits.row("Max delay per wait (ms):", maxDelay);
        limits.row("Backoff multiplier:", multiplier);
        limits.span(retryAfter);
        limits.row("Max total wait per request (s):", budget);
        limits.span(cooldown);
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.add(left(section("Coverage", coverage.panel)));
        page.add(Box.createVerticalStrut(8));
        page.add(left(section("Waiting (used by RETRY rules without their own delay / limit)", limits.panel)));
        page.add(Box.createVerticalStrut(8));
        page.add(left(section("Limits", new WrappedText(String.join("\n",
                "- Requests that get no response at all (timeouts, dropped connections) cannot be retried.",
                "- Scanner is off by default: retry waits add to measured response time and can cause false positives"
                        + " in time-based checks.",
                "- Retrying non-idempotent methods (POST, PUT, DELETE) on 5xx can repeat a side effect; restrict"
                        + " Methods or use a separate profile for them.",
                "- Signature blocks, challenge pages and IP bans return the same result on resend: STOP them instead"
                        + " of retrying.",
                "- While waiting, the Burp tool's thread is held. Unloading the extension releases every waiting"
                        + " request."), 760))));
        return page;
    }

    private JPanel rulesTab() {
        ruleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleTable.getColumnModel().getColumn(0).setMaxWidth(40);
        ruleTable.getColumnModel().getColumn(2).setMaxWidth(70);
        ruleTable.getColumnModel().getColumn(3).setMaxWidth(90);
        ruleTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) selectRule(ruleTable.getSelectedRow());
        });
        JButton add = StyledButton.primary("Add rule");
        JButton addPreset = StyledButton.neutral("Add preset");
        JButton duplicate = StyledButton.neutral("Duplicate");
        JButton remove = StyledButton.danger("Remove");
        JButton up = StyledButton.neutral("Up");
        JButton down = StyledButton.neutral("Down");
        add.addActionListener(event -> mutateRules(() -> {
            rules.add(RetryRule.of("New rule", RetryRule.Action.RETRY, List.of(RetryCondition.of(
                    RetryConditionSource.STATUS, "", StepConditionOperator.EQUALS, "429"))));
            return rules.size() - 1;
        }));
        addPreset.addActionListener(event -> mutateRules(() -> {
            RetryPresets.Preset preset = (RetryPresets.Preset) presetCombo.getSelectedItem();
            if (preset == null) return ruleIndex;
            int at = ruleIndex < 0 ? rules.size() : ruleIndex + 1;
            rules.add(at, preset.create());
            return at;
        }));
        duplicate.addActionListener(event -> mutateRules(() -> {
            if (ruleIndex < 0) return ruleIndex;
            rules.add(ruleIndex + 1, rules.get(ruleIndex).withNewId());
            return ruleIndex + 1;
        }));
        remove.addActionListener(event -> mutateRules(() -> {
            if (ruleIndex < 0) return ruleIndex;
            rules.remove(ruleIndex);
            return Math.min(ruleIndex, rules.size() - 1);
        }));
        up.addActionListener(event -> mutateRules(() -> move(rules, ruleIndex, -1)));
        down.addActionListener(event -> mutateRules(() -> move(rules, ruleIndex, 1)));
        presetCombo.addActionListener(event -> showPresetDescription());
        showPresetDescription();

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(add); actions.add(duplicate); actions.add(remove); actions.add(up); actions.add(down);
        actions.add(Box.createHorizontalStrut(12));
        actions.add(presetCombo); actions.add(addPreset);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(actions, BorderLayout.NORTH);
        JScrollPane tableScroll = new JScrollPane(ruleTable);
        tableScroll.setPreferredSize(new Dimension(600, 150));
        top.add(tableScroll, BorderLayout.CENTER);
        top.add(presetDescription, BorderLayout.SOUTH);

        buildRuleEditor();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, ruleEditor);
        split.setResizeWeight(0.35);
        split.setBorder(BorderFactory.createEmptyBorder());
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void buildRuleEditor() {
        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        fields.add(new JLabel("Name:")); fields.add(ruleName);
        fields.add(ruleEnabled);
        fields.add(new JLabel("Action:")); fields.add(ruleAction);
        fields.add(new JLabel("Fixed delay ms (blank = profile):")); fields.add(ruleDelay);
        fields.add(new JLabel("Max retries (blank = profile):")); fields.add(ruleMaxRetries);
        fields.add(new JLabel("Highlight:")); fields.add(ruleHighlight);

        conditionTable.setRowHeight(Math.max(conditionTable.getRowHeight(), 22));
        conditionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conditionTable.getColumnModel().getColumn(ConditionTableModel.SOURCE)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(RetryConditionSource.values())));
        conditionTable.getColumnModel().getColumn(ConditionTableModel.OPERATOR)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(StepConditionOperator.values())));
        int[] widths = {40, 55, 170, 140, 130, 260, 45, 40};
        for (int i = 0; i < widths.length; i++) conditionTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        conditionModel.addTableModelListener(event -> refreshExpression());

        JButton addAnd = StyledButton.primary("Add condition (AND)");
        JButton addOr = StyledButton.neutral("Add OR group");
        JButton remove = StyledButton.danger("Remove condition");
        addAnd.addActionListener(event -> {
            stopConditionEditing();
            int row = conditionTable.getSelectedRow();
            int group = row >= 0 ? conditionModel.rows.get(row).group : conditionModel.lastGroup();
            conditionModel.add(new ConditionRow(group));
        });
        addOr.addActionListener(event -> {
            stopConditionEditing();
            conditionModel.add(new ConditionRow(Math.min(RetryCondition.MAX_GROUP, conditionModel.lastGroup() + 1)));
        });
        remove.addActionListener(event -> {
            stopConditionEditing();
            int row = conditionTable.getSelectedRow();
            if (row >= 0) conditionModel.remove(row);
        });
        JPanel conditionActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        conditionActions.add(addAnd); conditionActions.add(addOr); conditionActions.add(remove);

        JPanel conditions = new JPanel(new BorderLayout(0, 4));
        conditions.setBorder(BorderFactory.createTitledBorder("Conditions: same group = AND, different groups = OR"));
        conditions.add(conditionActions, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(conditionTable);
        scroll.setPreferredSize(new Dimension(600, 160));
        conditions.add(scroll, BorderLayout.CENTER);
        JPanel preview = new JPanel(new BorderLayout(0, 2));
        preview.add(new WrappedText("Selector: header name for header sources, JSON path (e.g. $.error.code) for json."
                + " IN takes a comma-separated list. Text comparisons and regex ignore case unless Case is ticked."
                + " Not inverts a condition.", 900), BorderLayout.NORTH);
        preview.add(expression, BorderLayout.CENTER);
        conditions.add(preview, BorderLayout.SOUTH);

        ruleEditor.setBorder(BorderFactory.createTitledBorder("Selected rule"));
        ruleEditor.add(fields, BorderLayout.NORTH);
        ruleEditor.add(conditions, BorderLayout.CENTER);
    }

    private JPanel testTab() {
        testResponse.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        testResponse.setText("HTTP/1.1 429 Too Many Requests\nRetry-After: 5\nContent-Type: text/plain\n\nslow down");
        JButton evaluate = StyledButton.primary("Evaluate");
        evaluate.addActionListener(event -> runTest());
        JPanel request = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        request.add(new JLabel("Tool:")); request.add(testTool);
        request.add(new JLabel("Method:")); request.add(testMethod);
        request.add(new JLabel("Host:")); request.add(testHost);
        request.add(new JLabel("Path:")); request.add(testPath);
        request.add(testInScope);
        request.add(evaluate);
        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.add(new WrappedText("Paste a raw response (status line, headers, blank line, body) and describe the request."
                + " Evaluate shows which profile and rule would handle it using the current form, saved or not."
                + " Nothing is sent.", 900), BorderLayout.NORTH);
        north.add(request, BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(testResponse), BorderLayout.CENTER);
        panel.add(testResult, BorderLayout.SOUTH);
        return panel;
    }

    // ── Loading and committing ──────────────────────────────────────────────

    /** Replaces the working copy with a configuration. */
    void load(RetryConfig config) {
        updating = true;
        try {
            loaded = config;
            masterEnabled.setSelected(config.enabled());
            profiles.clear();
            profiles.addAll(config.profiles());
            profileIndex = -1;
            ruleIndex = -1;
            refreshProfileList();
        } finally {
            updating = false;
        }
        selectProfile(profiles.isEmpty() ? -1 : 0);
        showStatus(config.summary(), false);
    }

    /** Validates the form and saves it; returns whether it was saved. */
    boolean save() {
        try {
            RetryConfig config = currentConfig();
            repository.save(repository.load().withRetry(config));
            loaded = config;
            showStatus("Saved. " + config.summary(), false);
            return true;
        } catch (IllegalArgumentException exception) {
            showStatus(message(exception), true);
            return false;
        }
    }

    /** Commits the open editors and returns the configuration shown in the form. */
    RetryConfig currentConfig() {
        commitProfile();
        return new RetryConfig(masterEnabled.isSelected(), profiles);
    }

    /** Returns the profiles in the working copy (for tests). */
    List<RetryProfile> workingProfiles() {
        commitProfile();
        return List.copyOf(profiles);
    }

    /** Adds a preset to the selected profile (for tests and the preset button). */
    void addPreset(String name) {
        for (int i = 0; i < presetCombo.getItemCount(); i++) {
            if (presetCombo.getItemAt(i).name().equals(name)) {
                presetCombo.setSelectedIndex(i);
                mutateRules(() -> {
                    int at = ruleIndex < 0 ? rules.size() : ruleIndex + 1;
                    rules.add(at, presetCombo.getItemAt(presetCombo.getSelectedIndex()).create());
                    return at;
                });
                return;
            }
        }
        throw new IllegalArgumentException("Unknown preset " + name);
    }

    private void selectProfile(int index) {
        if (index == profileIndex && index >= 0) return;
        if (!tryCommit()) {
            updating = true;
            try {
                profileList.setSelectedIndex(profileIndex);
            } finally {
                updating = false;
            }
            return;
        }
        profileIndex = index;
        updating = true;
        try {
            if (profileList.getSelectedIndex() != index) profileList.setSelectedIndex(index);
            RetryProfile profile = index < 0 ? null : profiles.get(index);
            setProfileFormEnabled(profile != null);
            rules.clear();
            if (profile != null) {
                profileName.setText(profile.name());
                profileEnabled.setSelected(profile.enabled());
                tools.forEach((tool, box) -> box.setSelected(profile.toolTypes().contains(tool)));
                scopeOnly.setSelected(profile.burpScopeOnly());
                hostPattern.setText(profile.hostPattern());
                pathPattern.setText(profile.pathPattern());
                methods.setText(String.join(", ", profile.methods()));
                maxRetries.setValue(profile.maxRetries());
                baseDelay.setValue(profile.baseDelayMillis());
                maxDelay.setValue(profile.maxDelayMillis());
                multiplier.setValue(profile.backoffMultiplier());
                retryAfter.setSelected(profile.respectRetryAfter());
                budget.setValue(profile.maxTotalWaitSeconds());
                cooldown.setSelected(profile.hostCooldown());
                rules.addAll(profile.rules());
            }
            ruleIndex = -1;
            ruleTableModel.fireTableDataChanged();
        } finally {
            updating = false;
        }
        selectRule(rules.isEmpty() ? -1 : 0);
    }

    private void selectRule(int index) {
        if (index == ruleIndex && index >= 0) return;
        if (!tryCommitRule()) {
            updating = true;
            try {
                if (ruleIndex >= 0) ruleTable.setRowSelectionInterval(ruleIndex, ruleIndex);
            } finally {
                updating = false;
            }
            return;
        }
        ruleIndex = index;
        updating = true;
        try {
            if (index >= 0 && ruleTable.getSelectedRow() != index) ruleTable.setRowSelectionInterval(index, index);
            if (index < 0) ruleTable.clearSelection();
            RetryRule rule = index < 0 ? null : rules.get(index);
            setEnabledDeep(ruleEditor, rule != null);
            ruleName.setText(rule == null ? "" : rule.name());
            ruleEnabled.setSelected(rule != null && rule.enabled());
            ruleAction.setSelectedItem(rule == null ? RetryRule.Action.RETRY : rule.action());
            ruleDelay.setText(rule == null || rule.delayMillis() == null ? "" : rule.delayMillis().toString());
            ruleMaxRetries.setText(rule == null || rule.maxRetries() == null ? "" : rule.maxRetries().toString());
            ruleHighlight.setSelectedItem(rule == null ? RetryRule.Highlight.NONE : rule.highlight());
            conditionModel.load(rule == null ? List.of() : rule.conditions());
        } finally {
            updating = false;
        }
        refreshExpression();
    }

    private boolean tryCommit() {
        try {
            commitProfile();
            return true;
        } catch (IllegalArgumentException exception) {
            showStatus(message(exception), true);
            return false;
        }
    }

    private boolean tryCommitRule() {
        try {
            commitRule();
            return true;
        } catch (IllegalArgumentException exception) {
            showStatus(message(exception), true);
            return false;
        }
    }

    private void commitProfile() {
        commitRule();
        if (profileIndex < 0 || profileIndex >= profiles.size()) return;
        try {
            for (JSpinner spinner : List.of(maxRetries, baseDelay, maxDelay, multiplier, budget)) spinner.commitEdit();
        } catch (java.text.ParseException exception) {
            throw new IllegalArgumentException("Check the numeric waiting fields");
        }
        RetryProfile current = profiles.get(profileIndex);
        RetryProfile updated = new RetryProfile(current.id(), profileName.getText(), profileEnabled.isSelected(),
                tools.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList(),
                scopeOnly.isSelected(), hostPattern.getText(), pathPattern.getText(), splitList(methods.getText()),
                rules, ((Number) maxRetries.getValue()).intValue(), ((Number) baseDelay.getValue()).longValue(),
                ((Number) maxDelay.getValue()).longValue(), ((Number) multiplier.getValue()).doubleValue(),
                retryAfter.isSelected(), ((Number) budget.getValue()).intValue(), cooldown.isSelected());
        profiles.set(profileIndex, updated);
        updating = true;
        try {
            profileListModel.set(profileIndex, label(updated));
        } finally {
            updating = false;
        }
    }

    private void commitRule() {
        if (ruleIndex < 0 || ruleIndex >= rules.size()) return;
        stopConditionEditing();
        RetryRule current = rules.get(ruleIndex);
        RetryRule updated = new RetryRule(current.id(), ruleName.getText(), ruleEnabled.isSelected(),
                (RetryRule.Action) ruleAction.getSelectedItem(), conditionModel.conditions(),
                optionalLong(ruleDelay.getText(), "Fixed delay"), optionalInt(ruleMaxRetries.getText(), "Max retries"),
                (RetryRule.Highlight) ruleHighlight.getSelectedItem());
        rules.set(ruleIndex, updated);
        ruleTableModel.fireTableRowsUpdated(ruleIndex, ruleIndex);
    }

    private void mutateProfiles(java.util.function.IntSupplier change) {
        if (!tryCommit()) return;
        int target = change.getAsInt();
        profileIndex = -1;
        ruleIndex = -1;
        updating = true;
        try {
            refreshProfileList();
        } finally {
            updating = false;
        }
        selectProfile(Math.min(target, profiles.size() - 1));
    }

    private void mutateRules(java.util.function.IntSupplier change) {
        if (profileIndex < 0 || !tryCommitRule()) return;
        int target = change.getAsInt();
        ruleIndex = -1;
        updating = true;
        try {
            ruleTableModel.fireTableDataChanged();
        } finally {
            updating = false;
        }
        selectRule(Math.min(target, rules.size() - 1));
    }

    private void runTest() {
        try {
            commitProfile();
            RetryMessage message = RetryMessage.parse(testResponse.getText(), testMethod.getText().strip(),
                    testPath.getText().strip());
            RetryRequestContext request = new RetryRequestContext((String) testTool.getSelectedItem(),
                    testMethod.getText().strip(), testHost.getText().strip(), testPath.getText().strip(),
                    testInScope.isSelected());
            String result = tester.explain(profiles, request, message);
            testResult.setText(masterEnabled.isSelected() ? result
                    : result + "\nNote: global retry is currently disabled, so nothing happens on live traffic.");
        } catch (IllegalArgumentException exception) {
            testResult.setText(message(exception));
        }
        testResult.revalidate();
    }

    // ── Refresh helpers ─────────────────────────────────────────────────────

    private void refreshProfileList() {
        profileListModel.clear();
        for (RetryProfile profile : profiles) profileListModel.addElement(label(profile));
    }

    private void refreshExpression() {
        if (updating) return;
        try {
            expression.setText("Matches when:\n" + RetryRule.of("preview", RetryRule.Action.RETRY,
                    conditionModel.conditions()).expression());
        } catch (IllegalArgumentException exception) {
            expression.setText("Incomplete condition: " + message(exception));
        }
        expression.revalidate();
    }

    private void showPresetDescription() {
        RetryPresets.Preset preset = (RetryPresets.Preset) presetCombo.getSelectedItem();
        presetDescription.setText(preset == null ? " " : "Preset: " + preset.description());
        presetDescription.revalidate();
    }

    private void setProfileFormEnabled(boolean enabled) {
        for (JComponent component : List.of(profileName, profileEnabled, scopeOnly, hostPattern, pathPattern, methods,
                maxRetries, baseDelay, maxDelay, multiplier, retryAfter, budget, cooldown)) {
            component.setEnabled(enabled);
        }
        tools.values().forEach(box -> box.setEnabled(enabled));
    }

    private static void setEnabledDeep(Container container, boolean enabled) {
        for (Component child : container.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof Container nested) setEnabledDeep(nested, enabled);
        }
    }

    private void stopConditionEditing() {
        if (conditionTable.isEditing() && !conditionTable.getCellEditor().stopCellEditing()) {
            throw new IllegalArgumentException("Finish editing the condition first");
        }
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? UiTheme.pick(new Color(0xC0392B), new Color(0xFF7B72))
                : UIManager.getColor("Label.foreground"));
    }

    private static String label(RetryProfile profile) {
        return (profile.enabled() ? "" : "(off) ") + profile.name();
    }

    private static String message(IllegalArgumentException exception) {
        return exception.getMessage() == null ? "Check the retry settings." : exception.getMessage();
    }

    private static <T> int move(List<T> list, int index, int delta) {
        int target = index + delta;
        if (index < 0 || target < 0 || target >= list.size()) return index;
        T item = list.remove(index);
        list.add(target, item);
        return target;
    }

    private static Long optionalLong(String text, String label) {
        if (text == null || text.isBlank()) return null;
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number or blank");
        }
    }

    private static Integer optionalInt(String text, String label) {
        Long value = optionalLong(text, label);
        if (value == null) return null;
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) throw new IllegalArgumentException(label + " is too large");
        return value.intValue();
    }

    static List<String> splitList(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,\\s]+")).map(String::strip).filter(token -> !token.isEmpty()).toList();
    }

    private static JPanel section(String title, JComponent body) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        return component;
    }

    private static JScrollPane scrolled(JPanel content) {
        JPanel page = new WidthTrackingPanel();
        page.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        page.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    // ── Table models ────────────────────────────────────────────────────────

    private final class RuleTableModel extends AbstractTableModel {
        private final String[] columns = {"On", "Name", "Action", "Highlight", "Matches when"};

        @Override public int getRowCount() { return rules.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }

        @Override
        public Object getValueAt(int row, int column) {
            RetryRule rule = rules.get(row);
            return switch (column) {
                case 0 -> rule.enabled();
                case 1 -> rule.name();
                case 2 -> rule.action().name();
                case 3 -> rule.highlight() == RetryRule.Highlight.NONE ? "" : rule.highlight().name();
                default -> rule.expression().replace("\n", " ");
            };
        }
    }

    /** Mutable row backing the condition grid; validated when the rule is committed. */
    static final class ConditionRow {
        boolean enabled = true;
        int group;
        RetryConditionSource source = RetryConditionSource.STATUS;
        String selector = "";
        StepConditionOperator operator = StepConditionOperator.EQUALS;
        String value = "";
        boolean caseSensitive;
        boolean negate;

        ConditionRow(int group) {
            this.group = Math.max(1, group);
        }

        static ConditionRow of(RetryCondition condition) {
            ConditionRow row = new ConditionRow(condition.group());
            row.enabled = condition.enabled();
            row.source = condition.source();
            row.selector = condition.selector();
            row.operator = condition.operator();
            row.value = condition.value();
            row.caseSensitive = condition.caseSensitive();
            row.negate = condition.negate();
            return row;
        }

        RetryCondition toCondition() {
            return new RetryCondition(enabled, group, source, selector, operator, value, caseSensitive, negate);
        }
    }

    static final class ConditionTableModel extends AbstractTableModel {
        static final int SOURCE = 2;
        static final int OPERATOR = 4;
        private final String[] columns = {"On", "Group", "Source", "Header / JSON path", "Operator", "Value", "Case", "Not"};
        private final List<ConditionRow> rows = new ArrayList<>();

        void load(List<RetryCondition> conditions) {
            rows.clear();
            conditions.forEach(condition -> rows.add(ConditionRow.of(condition)));
            fireTableDataChanged();
        }

        void add(ConditionRow row) {
            rows.add(row);
            rows.sort(java.util.Comparator.comparingInt(candidate -> candidate.group));
            fireTableDataChanged();
        }

        void remove(int index) {
            rows.remove(index);
            fireTableDataChanged();
        }

        int lastGroup() {
            return rows.stream().mapToInt(row -> row.group).max().orElse(1);
        }

        List<RetryCondition> conditions() {
            List<RetryCondition> conditions = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                try {
                    conditions.add(rows.get(i).toCondition());
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Condition row " + (i + 1) + ": " + exception.getMessage());
                }
            }
            return conditions;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public boolean isCellEditable(int row, int column) { return true; }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0, 6, 7 -> Boolean.class;
                case 1 -> Integer.class;
                case SOURCE -> RetryConditionSource.class;
                case OPERATOR -> StepConditionOperator.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            ConditionRow row = rows.get(rowIndex);
            return switch (column) {
                case 0 -> row.enabled;
                case 1 -> row.group;
                case SOURCE -> row.source;
                case 3 -> row.selector;
                case OPERATOR -> row.operator;
                case 5 -> row.value;
                case 6 -> row.caseSensitive;
                default -> row.negate;
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            ConditionRow row = rows.get(rowIndex);
            switch (column) {
                case 0 -> row.enabled = Boolean.TRUE.equals(value);
                case 1 -> row.group = value instanceof Number number
                        ? Math.max(1, Math.min(RetryCondition.MAX_GROUP, number.intValue())) : row.group;
                case SOURCE -> row.source = value instanceof RetryConditionSource source ? source : row.source;
                case 3 -> row.selector = value == null ? "" : value.toString();
                case OPERATOR -> row.operator = value instanceof StepConditionOperator operator ? operator : row.operator;
                case 5 -> row.value = value == null ? "" : value.toString();
                case 6 -> row.caseSensitive = Boolean.TRUE.equals(value);
                default -> row.negate = Boolean.TRUE.equals(value);
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    /** Scroll page that follows the viewport width so wrapped guidance reflows. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        private WidthTrackingPanel() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Two-column label/field grid. */
    private static final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        void row(String label, JComponent field) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = row; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 0, 3, 10);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = row; fc.anchor = GridBagConstraints.WEST; fc.weightx = 1;
            fc.fill = GridBagConstraints.HORIZONTAL; fc.insets = new Insets(3, 0, 3, 0);
            panel.add(new JLabel(label), lc);
            panel.add(field, fc);
            row++;
        }

        void span(JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(3, 0, 3, 0);
            panel.add(field, c);
            row++;
        }
    }
}
