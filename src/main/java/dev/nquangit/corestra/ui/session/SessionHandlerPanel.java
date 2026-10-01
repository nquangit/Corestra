package dev.nquangit.corestra.ui.session;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.session.SessionAuthConfig;
import dev.nquangit.corestra.session.SessionCriterionType;
import dev.nquangit.corestra.session.SessionExtractionRule;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfileChangeEvent;
import dev.nquangit.corestra.session.SessionProfileChangeType;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionSchedulerConfig;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionScopeCriterion;
import dev.nquangit.corestra.session.SessionScopeMode;
import dev.nquangit.corestra.session.SessionState;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Swing editor for project-level Session Handler profiles and global state.
 */
public final class SessionHandlerPanel extends JPanel {
    private static final String[] COMMON_TOOL_TYPES = {"PROXY", "REPEATER", "SCANNER", "INTRUDER", "EXTENSIONS"};
    private static final int INJECTION_TOOLS_COLUMN = 6;
    private static final int INJECTION_VALUE_COLUMN = 3;
    private static final int EXTRACTION_TOOLS_COLUMN = 8;
    private static final DateTimeFormatter STATE_UPDATED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final SessionManager sessionManager;
    private final Supplier<List<FlowDefinition>> flowsSupplier;
    private final DefaultListModel<SessionProfile> profileListModel = new DefaultListModel<>();
    private final JList<SessionProfile> profileList = new JList<>(profileListModel);
    private final JTextField nameField = new JTextField();
    private final JCheckBox enabledCheck = new JCheckBox("Enabled");
    private final JComboBox<AuthSourceMode> authSourceCombo = new JComboBox<>(AuthSourceMode.values());
    private final JComboBox<AuthProfileOption> linkedAuthProfileCombo = new JComboBox<>();
    private final JComboBox<FlowOption> authFlowCombo = new JComboBox<>();
    private final JTextField ttlField = new JTextField();
    private final JLabel authSourceHint = new JLabel(" ");
    private final JComboBox<SessionScopeMode> scopeModeCombo = new JComboBox<>(SessionScopeMode.values());
    private final JCheckBox schedulerEnabledCheck = new JCheckBox("Enabled");
    private final JTextField schedulerIntervalField = new JTextField();
    private final JCheckBox schedulerStartupCheck = new JCheckBox("Run at startup");
    private final JCheckBox revealStateCheck = new JCheckBox("Reveal values");
    private final DefaultTableModel criteriaModel = editableModel("Type", "Selector", "Pattern");
    private final DefaultTableModel injectionModel = editableModel(
            "Enabled", "Target", "Selector", "Value Template", "Write Strategy", "Transforms (→)", "Tools");
    private final DefaultTableModel extractionModel = editableModel(
            "Enabled", "Source", "Extractor", "Selector", "Regex Input", "Group",
            "Target Key", "Transforms (→)", "Tools");
    private final DefaultTableModel stateModel = nonEditableModel("Key", "Value", "Updated");
    private final JTable criteriaTable = new JTable(criteriaModel);
    private final JTable injectionTable = new JTable(injectionModel);
    private final JTable extractionTable = new JTable(extractionModel);
    private final JTable stateTable = new JTable(stateModel);
    private final JLabel statusLabel = new JLabel(" ");
    private final Consumer<SessionProfileChangeEvent> profileChangeListener = this::handleProfileChange;
    private final AtomicBoolean stateRefreshQueued = new AtomicBoolean();
    private JButton addCriterionButton;
    private JButton removeCriterionButton;
    private boolean refreshing;
    private boolean saving;
    private String loadedProfileId = "";
    private volatile String queuedStateRefreshProfileId = "";

    /**
     * Creates a Session Handler panel.
     *
     * @param sessionManager session manager
     * @param flowsSupplier current Flowise flows supplier
     */
    public SessionHandlerPanel(SessionManager sessionManager, Supplier<List<FlowDefinition>> flowsSupplier) {
        super(new BorderLayout(10, 10));
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.flowsSupplier = Objects.requireNonNull(flowsSupplier, "flowsSupplier");
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        installTableEditors();
        add(buildProfileSide(), BorderLayout.WEST);
        add(buildEditor(), BorderLayout.CENTER);
        wireListeners();
        sessionManager.addProfileChangeListener(profileChangeListener);
        refreshProfiles(SessionProfile.GENERAL_ID);
    }

    /**
     * Reloads profiles and flow options from the backing manager.
     */
    public void refreshProfiles() {
        SessionProfile selected = profileList.getSelectedValue();
        refreshProfiles(selected == null ? SessionProfile.GENERAL_ID : selected.id());
    }

    private void installTableEditors() {
        criteriaTable.getColumnModel().getColumn(0)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(SessionCriterionType.values())));
        injectionTable.getColumnModel().getColumn(1)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(InjectRule.Target.values())));
        injectionTable.getColumnModel().getColumn(4)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(InjectRule.WriteStrategy.values())));
        injectionTable.getColumnModel().getColumn(INJECTION_VALUE_COLUMN)
                .setCellEditor(new SessionVariableTemplateCellEditor(extractionModel));
        extractionTable.getColumnModel().getColumn(1)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(ExtractRule.SourceSide.values())));
        extractionTable.getColumnModel().getColumn(2)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(ExtractRule.ExtractorType.values())));
        extractionTable.getColumnModel().getColumn(4)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(ExtractRule.RegexSource.values())));
        installToolEditor(injectionTable.getColumnModel().getColumn(INJECTION_TOOLS_COLUMN));
        installToolEditor(extractionTable.getColumnModel().getColumn(EXTRACTION_TOOLS_COLUMN));
    }

    private void installToolEditor(TableColumn column) {
        column.setCellEditor(new ToolSourceCellEditor(this));
        column.setCellRenderer(new ToolSourceCellRenderer());
    }

    private JPanel buildProfileSide() {
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SessionProfile profile) {
                    label.setText(profile.toString());
                }
                return label;
            }
        });

        JButton addButton = new JButton("Add");
        JButton deleteButton = new JButton("Delete");
        addButton.addActionListener(event -> addProfile());
        deleteButton.addActionListener(event -> deleteSelectedProfile());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(addButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(deleteButton);
        buttons.add(Box.createHorizontalGlue());

        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setPreferredSize(new Dimension(240, 500));
        side.add(new JLabel("Profiles"), BorderLayout.NORTH);
        side.add(new JScrollPane(profileList), BorderLayout.CENTER);
        side.add(buttons, BorderLayout.SOUTH);
        return side;
    }

    private JPanel buildEditor() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", buildGeneralTab());
        tabs.addTab("Scope", buildScopeTab());
        tabs.addTab("Injection", buildRuleTab(injectionTable, this::addInjectionRule, this::removeSelectedInjectionRule));
        tabs.addTab("Extraction", buildRuleTab(extractionTable, this::addExtractionRule, this::removeSelectedExtractionRule));
        tabs.addTab("Scheduler", buildSchedulerTab());
        tabs.addTab("State", buildStateTab());
        tabs.setToolTipTextAt(2, "Optional traffic-specific rules applied after linked Auth Provider injection rules.");
        tabs.setToolTipTextAt(3, "Can update standalone state or the linked actor's auth variables from live traffic.");

        JButton runAuthButton = new JButton("Run Auth Now");
        runAuthButton.addActionListener(event -> runSelectedAuthFlow());

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(runAuthButton);
        actions.add(Box.createHorizontalStrut(10));
        actions.add(statusLabel);
        actions.add(Box.createHorizontalGlue());

        JPanel editor = new JPanel(new BorderLayout(0, 8));
        editor.add(tabs, BorderLayout.CENTER);
        editor.add(actions, BorderLayout.SOUTH);
        return editor;
    }

    private JPanel buildGeneralTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        addLabeled(panel, 0, "Name:", nameField);
        addLabeled(panel, 1, "Enabled:", enabledCheck);
        addLabeled(panel, 2, "Authentication:", authSourceCombo);
        addLabeled(panel, 3, "Shared Auth Profile:", buildLinkedAuthProfileSelector());
        addLabeled(panel, 4, "Standalone auth flow:", authFlowCombo);
        addLabeled(panel, 5, "Standalone TTL seconds:", ttlField);
        addLabeled(panel, 6, "", authSourceHint);
        addFiller(panel, 7);
        return panel;
    }

    private JPanel buildLinkedAuthProfileSelector() {
        JButton reload = new JButton("Reload");
        reload.setToolTipText("Reload shared profiles from Authentication.");
        reload.addActionListener(event -> {
            AuthProfileOption selected = (AuthProfileOption) linkedAuthProfileCombo.getSelectedItem();
            refreshLinkedAuthProfileOptions(selected == null ? "" : selected.id());
            updateAuthSourceControls();
        });
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(linkedAuthProfileCombo, BorderLayout.CENTER);
        row.add(reload, BorderLayout.EAST);
        return row;
    }

    private JPanel buildScopeTab() {
        criteriaTable.setFillsViewportHeight(true);
        addCriterionButton = new JButton("Add Criterion");
        removeCriterionButton = new JButton("Remove");
        addCriterionButton.addActionListener(event -> criteriaModel.addRow(new Object[]{SessionCriterionType.HOST, "", ""}));
        removeCriterionButton.addActionListener(event -> removeSelectedRow(criteriaTable, criteriaModel));

        JPanel buttons = rowButtons(addCriterionButton, removeCriterionButton);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(new JLabel("Scope mode:"), BorderLayout.WEST);
        top.add(scopeModeCombo, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(criteriaTable), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildRuleTab(JTable table, Runnable addAction, Runnable removeAction) {
        table.setFillsViewportHeight(true);
        JButton addButton = new JButton("Add Rule");
        JButton removeButton = new JButton("Remove");
        addButton.addActionListener(event -> addAction.run());
        removeButton.addActionListener(event -> removeAction.run());

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(rowButtons(addButton, removeButton), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildSchedulerTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        addLabeled(panel, 0, "Scheduler:", schedulerEnabledCheck);
        addLabeled(panel, 1, "Interval seconds:", schedulerIntervalField);
        addLabeled(panel, 2, "Startup:", schedulerStartupCheck);
        addFiller(panel, 3);
        return panel;
    }

    private JPanel buildStateTab() {
        stateTable.setFillsViewportHeight(true);
        JButton clearButton = new JButton("Clear State");
        clearButton.addActionListener(event -> clearSelectedState());
        revealStateCheck.addActionListener(event -> loadSelectedProfile());

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(revealStateCheck);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(clearButton);
        controls.add(Box.createHorizontalGlue());

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JScrollPane(stateTable), BorderLayout.CENTER);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private void wireListeners() {
        profileList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                SessionProfile selected = profileList.getSelectedValue();
                if (!refreshing
                        && selected != null
                        && !loadedProfileId.isBlank()
                        && !selected.id().equals(loadedProfileId)
                        && !saveCurrentProfileAfterStoppingEditors()) {
                    selectProfileWithoutLoading(loadedProfileId);
                    return;
                }
                loadSelectedProfile();
            }
        });
        nameField.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        nameField.addFocusListener(onFocusLost(this::saveCurrentProfileAfterStoppingEditors));
        ttlField.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        ttlField.addFocusListener(onFocusLost(this::saveCurrentProfileAfterStoppingEditors));
        enabledCheck.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        authSourceCombo.addActionListener(event -> {
            if (!refreshing
                    && authSourceCombo.getSelectedItem() == AuthSourceMode.LINKED_AUTH_PROFILE
                    && linkedAuthProfileCombo.getItemCount() > 1
                    && ((AuthProfileOption) linkedAuthProfileCombo.getSelectedItem()).id().isBlank()) {
                linkedAuthProfileCombo.setSelectedIndex(1);
            }
            updateAuthSourceControls();
            saveCurrentProfileAfterStoppingEditors();
        });
        linkedAuthProfileCombo.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        authFlowCombo.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        scopeModeCombo.addActionListener(event -> {
            updateCriteriaEditingState();
            saveCurrentProfileAfterStoppingEditors();
        });
        schedulerEnabledCheck.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        schedulerIntervalField.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());
        schedulerIntervalField.addFocusListener(onFocusLost(this::saveCurrentProfileAfterStoppingEditors));
        schedulerStartupCheck.addActionListener(event -> saveCurrentProfileAfterStoppingEditors());

        criteriaModel.addTableModelListener(event -> saveCurrentProfileFromUi());
        injectionModel.addTableModelListener(event -> saveCurrentProfileFromUi());
        extractionModel.addTableModelListener(event -> saveCurrentProfileFromUi());
    }

    private void handleProfileChange(SessionProfileChangeEvent event) {
        if (event == null
                || (event.type() != SessionProfileChangeType.STATE_UPDATED
                && event.type() != SessionProfileChangeType.STATE_CLEARED)) {
            return;
        }
        scheduleSelectedStateRefresh(event.profileId());
    }

    private void scheduleSelectedStateRefresh(String profileId) {
        queuedStateRefreshProfileId = profileId == null ? "" : profileId;
        if (stateRefreshQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::drainQueuedStateRefresh);
        }
    }

    private void drainQueuedStateRefresh() {
        String profileId = queuedStateRefreshProfileId;
        queuedStateRefreshProfileId = "";
        try {
            refreshSelectedState(profileId);
        } finally {
            stateRefreshQueued.set(false);
            if (!queuedStateRefreshProfileId.isBlank()) {
                scheduleSelectedStateRefresh(queuedStateRefreshProfileId);
            }
        }
    }

    private void refreshSelectedState(String profileId) {
        if (refreshing || saving || profileId == null || profileId.isBlank() || !profileId.equals(loadedProfileId)) {
            return;
        }
        SessionProfile latest = sessionManager.profiles().stream()
                .filter(profile -> profile.id().equals(profileId))
                .findFirst()
                .orElse(null);
        if (latest == null) {
            return;
        }
        refreshing = true;
        try {
            for (int i = 0; i < profileListModel.size(); i++) {
                if (profileListModel.get(i).id().equals(latest.id())) {
                    profileListModel.set(i, latest);
                    profileList.setSelectedIndex(i);
                    break;
                }
            }
            fillState(latest);
            setStatus("State updated");
        } finally {
            refreshing = false;
        }
    }

    private void refreshProfiles(String selectedId) {
        refreshing = true;
        try {
            refreshFlowOptions("");
            refreshLinkedAuthProfileOptions("");
            profileListModel.clear();
            int selectedIndex = 0;
            List<SessionProfile> profiles = sessionManager.profiles();
            for (int i = 0; i < profiles.size(); i++) {
                SessionProfile profile = profiles.get(i);
                profileListModel.addElement(profile);
                if (profile.id().equals(selectedId)) {
                    selectedIndex = i;
                }
            }
            if (!profileListModel.isEmpty()) {
                profileList.setSelectedIndex(selectedIndex);
            }
        } finally {
            refreshing = false;
        }
        loadSelectedProfile();
    }

    private void selectProfileWithoutLoading(String profileId) {
        refreshing = true;
        try {
            for (int i = 0; i < profileListModel.size(); i++) {
                if (profileListModel.get(i).id().equals(profileId)) {
                    profileList.setSelectedIndex(i);
                    return;
                }
            }
        } finally {
            refreshing = false;
        }
    }

    private void updateProfileListElement(SessionProfile saved) {
        refreshing = true;
        try {
            SessionProfile selected = profileList.getSelectedValue();
            boolean savedProfileIsSelected = selected != null && selected.id().equals(saved.id());
            for (int i = 0; i < profileListModel.size(); i++) {
                if (profileListModel.get(i).id().equals(saved.id())) {
                    profileListModel.set(i, saved);
                    if (savedProfileIsSelected) {
                        profileList.setSelectedIndex(i);
                    }
                    return;
                }
            }
        } finally {
            refreshing = false;
        }
    }

    private void updateCriteriaEditingState() {
        boolean customScope = scopeModeCombo.getSelectedItem() == SessionScopeMode.CUSTOM;
        criteriaTable.setEnabled(customScope);
        criteriaTable.setFocusable(customScope);
        criteriaTable.setRowSelectionAllowed(customScope);
        if (!customScope && criteriaTable.isEditing()) {
            criteriaTable.getCellEditor().cancelCellEditing();
        }
        if (addCriterionButton != null) {
            addCriterionButton.setEnabled(customScope);
        }
        if (removeCriterionButton != null) {
            removeCriterionButton.setEnabled(customScope);
        }
        criteriaTable.setToolTipText(customScope ? null : "Criteria are ignored while using Burp Target scope.");
    }

    private void setStatus(String message) {
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
    }

    private void refreshFlowOptions(String selectedFlowId) {
        authFlowCombo.removeAllItems();
        authFlowCombo.addItem(new FlowOption("", "(none)"));
        for (FlowDefinition flow : flowsSupplier.get()) {
            authFlowCombo.addItem(new FlowOption(flow.id(), flow.name()));
        }
        selectFlowOption(selectedFlowId);
    }

    private void selectFlowOption(String flowId) {
        String id = flowId == null ? "" : flowId;
        for (int i = 0; i < authFlowCombo.getItemCount(); i++) {
            FlowOption option = authFlowCombo.getItemAt(i);
            if (option.id().equals(id)) {
                authFlowCombo.setSelectedIndex(i);
                return;
            }
        }
        authFlowCombo.setSelectedIndex(0);
    }

    private void refreshLinkedAuthProfileOptions(String selectedProfileId) {
        linkedAuthProfileCombo.removeAllItems();
        linkedAuthProfileCombo.addItem(new AuthProfileOption("", "(select an Auth Profile)", ""));
        for (AuthProfile profile : sessionManager.linkedAuthProfiles()) {
            linkedAuthProfileCombo.addItem(new AuthProfileOption(profile.id(), profile.name(), profile.role()));
        }
        String id = selectedProfileId == null ? "" : selectedProfileId;
        for (int i = 0; i < linkedAuthProfileCombo.getItemCount(); i++) {
            if (linkedAuthProfileCombo.getItemAt(i).id().equals(id)) {
                linkedAuthProfileCombo.setSelectedIndex(i);
                return;
            }
        }
        if (!id.isBlank()) {
            linkedAuthProfileCombo.addItem(new AuthProfileOption(id, "Missing Auth Profile", ""));
            linkedAuthProfileCombo.setSelectedIndex(linkedAuthProfileCombo.getItemCount() - 1);
        } else {
            linkedAuthProfileCombo.setSelectedIndex(0);
        }
    }

    private void updateAuthSourceControls() {
        boolean linked = authSourceCombo.getSelectedItem() == AuthSourceMode.LINKED_AUTH_PROFILE;
        linkedAuthProfileCombo.setEnabled(linked && sessionManager.linkedAuthAvailable());
        authFlowCombo.setEnabled(!linked);
        ttlField.setEnabled(!linked);
        authSourceHint.setText(linked
                ? "Uses the selected actor's credentials, auth state, provider flows, and injection rules."
                : "Uses this Session Profile's flow, state, TTL, and injection rules.");
        authSourceHint.setToolTipText(linked
                ? "Local Session injection rules remain available as optional traffic-specific overrides."
                : "Choose Linked Auth Profile to reuse an actor configured under Authentication.");
    }

    private void loadSelectedProfile() {
        if (refreshing) {
            return;
        }
        SessionProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            return;
        }
        refreshing = true;
        try {
            nameField.setText(profile.name());
            nameField.setEnabled(!profile.isGeneral());
            enabledCheck.setSelected(profile.enabled());
            AuthSourceMode sourceMode = profile.usesLinkedAuthProfile()
                    ? AuthSourceMode.LINKED_AUTH_PROFILE : AuthSourceMode.STANDALONE_FLOW;
            authSourceCombo.setSelectedItem(sourceMode);
            refreshLinkedAuthProfileOptions(profile.linkedAuthProfileId());
            refreshFlowOptions(profile.authConfig().mainAuthFlowId());
            ttlField.setText(Long.toString(profile.authConfig().ttlSeconds()));
            scopeModeCombo.setSelectedItem(profile.scope().mode());
            schedulerEnabledCheck.setSelected(profile.scheduler().enabled());
            schedulerIntervalField.setText(Long.toString(profile.scheduler().intervalSeconds()));
            schedulerStartupCheck.setSelected(profile.scheduler().runAtStartup());
            fillCriteria(profile);
            fillInjectionRules(profile);
            fillExtractionRules(profile);
            fillState(profile);
            loadedProfileId = profile.id();
            updateAuthSourceControls();
            updateCriteriaEditingState();
            setStatus(" ");
        } finally {
            refreshing = false;
        }
    }

    private void fillCriteria(SessionProfile profile) {
        criteriaModel.setRowCount(0);
        profile.scope().criteria().forEach(criterion ->
                criteriaModel.addRow(new Object[]{criterion.type(), criterion.selector(), criterion.pattern()}));
    }

    private void fillInjectionRules(SessionProfile profile) {
        injectionModel.setRowCount(0);
        profile.injectionRules().forEach(rule -> injectionModel.addRow(new Object[]{
                rule.enabled(),
                rule.target(),
                rule.targetSelector(),
                rule.valueTemplate(),
                rule.writeStrategy(),
                formatTransforms(rule.transformChain()),
                formatTools(rule.allowedToolTypes())}));
    }

    private void fillExtractionRules(SessionProfile profile) {
        extractionModel.setRowCount(0);
        profile.extractionRules().forEach(rule -> extractionModel.addRow(new Object[]{
                rule.enabled(),
                rule.sourceSide(),
                rule.extractorType(),
                rule.sourceSelector(),
                rule.regexSource(),
                rule.regexGroupIndex() == null ? 0 : rule.regexGroupIndex(),
                rule.targetKey(),
                formatTransforms(rule.transformChain()),
                formatTools(rule.allowedToolTypes())}));
    }

    private void fillState(SessionProfile profile) {
        stateModel.setRowCount(0);
        String updated = formatStateUpdatedAt(sessionManager.displayStateUpdatedAt(profile));
        sessionManager.displayStateValues(profile).forEach((key, value) ->
                stateModel.addRow(new Object[]{key, revealStateCheck.isSelected() ? value : "********", updated}));
    }

    static String formatStateUpdatedAt(long epochMillis) {
        if (epochMillis <= 0L) {
            return "";
        }
        return STATE_UPDATED_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private void addProfile() {
        String defaultLinkedProfileId = sessionManager.linkedAuthProfiles().stream()
                .filter(AuthProfile::enabled)
                .map(AuthProfile::id)
                .findFirst()
                .orElse("");
        SessionProfile profile = new SessionProfile(
                UUID.randomUUID().toString(),
                "New Profile",
                true,
                false,
                SessionScope.burpTargetScope(),
                defaultLinkedProfileId,
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
        sessionManager.saveProfile(profile);
        refreshProfiles(profile.id());
    }

    private void deleteSelectedProfile() {
        SessionProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            return;
        }
        if (profile.isGeneral()) {
            JOptionPane.showMessageDialog(this, "The General profile cannot be deleted.",
                    "Session Handler", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this, "Delete profile \"" + profile.name() + "\"?",
                "Delete Session Profile", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            sessionManager.deleteProfile(profile.id());
            refreshProfiles(SessionProfile.GENERAL_ID);
        }
    }

    private boolean saveCurrentProfileFromUi() {
        if (refreshing || saving || loadedProfileId.isBlank()) {
            return true;
        }
        return saveProfileFromUi(loadedProfileId);
    }

    private boolean saveCurrentProfileAfterStoppingEditors() {
        if (refreshing || saving || loadedProfileId.isBlank()) {
            return true;
        }
        saving = true;
        try {
            stopEditing();
        } finally {
            saving = false;
        }
        return saveProfileFromUi(loadedProfileId);
    }

    private boolean saveProfileFromUi(String profileId) {
        if (refreshing || saving || profileId == null || profileId.isBlank()) {
            return true;
        }
        SessionProfile existing = sessionManager.profiles().stream()
                .filter(profile -> profile.id().equals(profileId))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            return true;
        }
        saving = true;
        try {
            SessionProfile saved = new SessionProfile(
                    existing.id(),
                    existing.isGeneral() ? "General" : nameField.getText(),
                    enabledCheck.isSelected(),
                    existing.systemProfile(),
                    readScope(),
                    selectedLinkedAuthProfileId(),
                    readAuthConfig(),
                    readSchedulerConfig(),
                    existing.state(),
                    readInjectionRules(),
                    readExtractionRules());
            sessionManager.saveProfile(saved);
            updateProfileListElement(saved);
            setStatus("Saved");
            return true;
        } catch (RuntimeException exception) {
            setStatus("Not saved: " + exception.getMessage());
            return false;
        } finally {
            saving = false;
        }
    }

    private SessionScope readScope() {
        SessionScopeMode mode = (SessionScopeMode) scopeModeCombo.getSelectedItem();
        return new SessionScope(mode == null ? SessionScopeMode.BURP_TARGET_SCOPE : mode, readCriteria());
    }

    private List<SessionScopeCriterion> readCriteria() {
        ArrayList<SessionScopeCriterion> criteria = new ArrayList<>();
        for (int row = 0; row < criteriaModel.getRowCount(); row++) {
            criteria.add(new SessionScopeCriterion(
                    enumValue(SessionCriterionType.class, valueAt(criteriaModel, row, 0), SessionCriterionType.HOST),
                    valueAt(criteriaModel, row, 1),
                    valueAt(criteriaModel, row, 2)));
        }
        return List.copyOf(criteria);
    }

    private SessionAuthConfig readAuthConfig() {
        FlowOption option = (FlowOption) authFlowCombo.getSelectedItem();
        return new SessionAuthConfig(option == null ? "" : option.id(), parseLong(ttlField.getText(), 0L), List.of());
    }

    private String selectedLinkedAuthProfileId() {
        if (authSourceCombo.getSelectedItem() != AuthSourceMode.LINKED_AUTH_PROFILE) {
            return "";
        }
        AuthProfileOption option = (AuthProfileOption) linkedAuthProfileCombo.getSelectedItem();
        if (option == null || option.id().isBlank()) {
            throw new IllegalArgumentException("Select a shared Auth Profile");
        }
        return option.id();
    }

    private SessionSchedulerConfig readSchedulerConfig() {
        return new SessionSchedulerConfig(
                schedulerEnabledCheck.isSelected(),
                parseLong(schedulerIntervalField.getText(), 300L),
                schedulerStartupCheck.isSelected());
    }

    private List<SessionInjectionRule> readInjectionRules() {
        ArrayList<SessionInjectionRule> rules = new ArrayList<>();
        for (int row = 0; row < injectionModel.getRowCount(); row++) {
            rules.add(new SessionInjectionRule(
                    UUID.randomUUID().toString(),
                    boolAt(injectionModel, row, 0),
                    enumValue(InjectRule.Target.class, valueAt(injectionModel, row, 1), InjectRule.Target.REQUEST_HEADER),
                    valueAt(injectionModel, row, 2),
                    valueAt(injectionModel, row, 3),
                    enumValue(InjectRule.WriteStrategy.class, valueAt(injectionModel, row, 4),
                            InjectRule.WriteStrategy.OVERWRITE),
                    splitTools(valueAt(injectionModel, row, 6)),
                    splitTransforms(rawValueAt(injectionModel, row, 5))));
        }
        return List.copyOf(rules);
    }

    private List<SessionExtractionRule> readExtractionRules() {
        ArrayList<SessionExtractionRule> rules = new ArrayList<>();
        for (int row = 0; row < extractionModel.getRowCount(); row++) {
            rules.add(new SessionExtractionRule(
                    UUID.randomUUID().toString(),
                    boolAt(extractionModel, row, 0),
                    enumValue(ExtractRule.SourceSide.class, valueAt(extractionModel, row, 1), ExtractRule.SourceSide.RESPONSE),
                    enumValue(ExtractRule.ExtractorType.class, valueAt(extractionModel, row, 2), ExtractRule.ExtractorType.HEADER),
                    valueAt(extractionModel, row, 3),
                    valueAt(extractionModel, row, 6),
                    splitTools(valueAt(extractionModel, row, 8)),
                    splitTransforms(rawValueAt(extractionModel, row, 7)),
                    enumValue(ExtractRule.RegexSource.class, valueAt(extractionModel, row, 4),
                            ExtractRule.RegexSource.BODY),
                    parseNonNegativeInt(valueAt(extractionModel, row, 5), 0)));
        }
        return List.copyOf(rules);
    }

    private void addInjectionRule() {
        injectionModel.addRow(new Object[]{
                true,
                InjectRule.Target.REQUEST_HEADER,
                "Authorization",
                "Bearer {{session.accessToken}}",
                InjectRule.WriteStrategy.OVERWRITE,
                "",
                ""});
    }

    private void removeSelectedInjectionRule() {
        removeSelectedRow(injectionTable, injectionModel);
    }

    private void addExtractionRule() {
        extractionModel.addRow(new Object[]{
                true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.JSON_PATH,
                "accessToken",
                ExtractRule.RegexSource.BODY,
                0,
                "accessToken",
                "",
                ""});
    }

    private void removeSelectedExtractionRule() {
        removeSelectedRow(extractionTable, extractionModel);
    }

    private void clearSelectedState() {
        SessionProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            return;
        }
        String detail = profile.usesLinkedAuthProfile()
                ? "This clears the linked actor's shared auth state while preserving credentials."
                : "This clears the standalone Session state.";
        int choice = JOptionPane.showConfirmDialog(this,
                "Clear stored values for \"" + profile.name() + "\"?\n" + detail,
                "Clear Session State", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            sessionManager.clearState(profile.id());
            refreshProfiles(profile.id());
        }
    }

    private void runSelectedAuthFlow() {
        SessionProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            return;
        }
        if (!saveCurrentProfileAfterStoppingEditors()) {
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                sessionManager.forceAuthenticate(profile.id());
                return null;
            }

            @Override
            protected void done() {
                refreshProfiles(profile.id());
            }
        }.execute();
    }

    private static FocusAdapter onFocusLost(Runnable action) {
        return new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                action.run();
            }
        };
    }

    private void stopEditing() {
        stopEditing(criteriaTable);
        stopEditing(injectionTable);
        stopEditing(extractionTable);
    }

    private static void stopEditing(JTable table) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static void addLabeled(JPanel panel, int row, String label, java.awt.Component component) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 0, 4, 8);
        panel.add(new JLabel(label), lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = row;
        fc.weightx = 1;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(4, 0, 4, 0);
        panel.add(component, fc);
    }

    private static void addFiller(JPanel panel, int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createVerticalGlue(), gbc);
    }

    private static JPanel rowButtons(JButton addButton, JButton removeButton) {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(addButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(removeButton);
        buttons.add(Box.createHorizontalGlue());
        return buttons;
    }

    private static DefaultTableModel editableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 && "Enabled".equals(getColumnName(columnIndex))
                        ? Boolean.class
                        : String.class;
            }
        };
    }

    private static DefaultTableModel nonEditableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static void removeSelectedRow(JTable table, DefaultTableModel model) {
        int row = table.getSelectedRow();
        if (row >= 0) {
            model.removeRow(table.convertRowIndexToModel(row));
        }
    }

    private static String valueAt(DefaultTableModel model, int row, int col) {
        return rawValueAt(model, row, col).strip();
    }

    private static String rawValueAt(DefaultTableModel model, int row, int col) {
        Object value = model.getValueAt(row, col);
        return value == null ? "" : value.toString();
    }

    private static boolean boolAt(DefaultTableModel model, int row, int col) {
        Object value = model.getValueAt(row, col);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw == null ? "" : raw.strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseNonNegativeInt(String raw, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(raw == null ? "" : raw.strip()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static String formatTransforms(List<String> transforms) {
        return transforms == null || transforms.isEmpty() ? "" : String.join("→", transforms);
    }

    static List<String> splitTransforms(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("→", -1))
                .map(String::stripLeading)
                .filter(value -> !value.isBlank())
                .toList();
    }

    static String formatTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        return String.join(",", tools);
    }

    static String displayTools(Object value) {
        String raw = value == null ? "" : value.toString().strip();
        return raw.isBlank() ? "All tools" : raw;
    }

    static List<String> splitTools(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .forEach(value -> values.put(value, value));
        return List.copyOf(values.keySet());
    }

    static List<String> availableSessionVariableTemplates(DefaultTableModel extractionModel) {
        LinkedHashMap<String, String> templates = new LinkedHashMap<>();
        for (int row = 0; row < extractionModel.getRowCount(); row++) {
            if (!boolAt(extractionModel, row, 0)) {
                continue;
            }
            String key = valueAt(extractionModel, row, 6);
            if (key.startsWith("session.")) {
                key = key.substring("session.".length());
            }
            if (!key.isBlank()) {
                String template = "{{session." + key + "}}";
                templates.put(template, template);
            }
        }
        return List.copyOf(templates.keySet());
    }

    private static <T extends Enum<T>> T enumValue(Class<T> enumType, String raw, T fallback) {
        if (raw != null && !raw.isBlank()) {
            try {
                return Enum.valueOf(enumType, raw.strip());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static final class ToolSourceCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setText(displayTools(value));
            return component;
        }
    }

    private static final class ToolSourceCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final Component parent;
        private final JButton button = new JButton();
        private String value = "";

        private ToolSourceCellEditor(Component parent) {
            this.parent = parent;
            button.addActionListener(event -> chooseTools());
        }

        @Override
        public Object getCellEditorValue() {
            return value;
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table,
                Object value,
                boolean isSelected,
                int row,
                int column) {
            this.value = value == null ? "" : value.toString();
            button.setText(displayTools(this.value));
            return button;
        }

        private void chooseTools() {
            JCheckBox allTools = new JCheckBox("All tools");
            List<String> selected = splitTools(value);
            allTools.setSelected(selected.isEmpty());
            List<JCheckBox> boxes = new ArrayList<>();
            JPanel panel = new JPanel(new GridLayout(0, 1, 2, 2));
            panel.add(allTools);
            for (String toolType : COMMON_TOOL_TYPES) {
                JCheckBox box = new JCheckBox(toolType, selected.contains(toolType));
                boxes.add(box);
                panel.add(box);
            }
            allTools.addActionListener(event -> boxes.forEach(box -> box.setEnabled(!allTools.isSelected())));
            boxes.forEach(box -> box.setEnabled(!allTools.isSelected()));

            int choice = JOptionPane.showConfirmDialog(
                    parent,
                    panel,
                    "Tool Sources",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (choice == JOptionPane.OK_OPTION) {
                if (allTools.isSelected()) {
                    value = "";
                } else {
                    value = boxes.stream()
                            .filter(JCheckBox::isSelected)
                            .map(JCheckBox::getText)
                            .reduce((left, right) -> left + "," + right)
                            .orElse("");
                }
            }
            fireEditingStopped();
        }
    }

    private static final class SessionVariableTemplateCellEditor extends AbstractCellEditor
            implements TableCellEditor {
        private final DefaultTableModel extractionModel;
        private JComboBox<String> combo;

        private SessionVariableTemplateCellEditor(DefaultTableModel extractionModel) {
            this.extractionModel = extractionModel;
        }

        @Override
        public Object getCellEditorValue() {
            Object value = combo == null ? "" : combo.getEditor().getItem();
            return value == null ? "" : value.toString();
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table,
                Object value,
                boolean isSelected,
                int row,
                int column) {
            combo = new JComboBox<>(availableSessionVariableTemplates(extractionModel).toArray(String[]::new));
            combo.setEditable(true);
            combo.getEditor().setItem(value == null ? "" : value.toString());
            return combo;
        }
    }

    private record FlowOption(String id, String name) {
        @Override
        public String toString() {
            return id.isBlank() ? name : name + " (" + id + ")";
        }
    }

    private enum AuthSourceMode {
        LINKED_AUTH_PROFILE("Linked Auth Profile (recommended)"),
        STANDALONE_FLOW("Standalone Flow (legacy)");

        private final String label;

        AuthSourceMode(String label) {
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    private record AuthProfileOption(String id, String name, String role) {
        @Override public String toString() {
            if (id.isBlank()) return name;
            return role == null || role.isBlank() ? name : name + " — " + role;
        }
    }
}
