package dev.nquangit.corestra.ui.session;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;
import dev.nquangit.corestra.session.SessionAuthConfig;
import dev.nquangit.corestra.session.SessionExtractionRule;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfileRepository;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionSchedulerConfig;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionScopeMode;
import dev.nquangit.corestra.session.SessionState;
import dev.nquangit.corestra.session.SessionTrafficContext;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHandlerPanelTest {

    @Test
    void profileListDisplaysProfileNames() throws Exception {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(profile("p1", "Owner", false));
        SessionHandlerPanel panel = panel(repository);
        @SuppressWarnings("rawtypes")
        JList list = field(panel, "profileList", JList.class);

        String general = ((JLabel) list.getCellRenderer().getListCellRendererComponent(
                list, list.getModel().getElementAt(0), 0, false, false)).getText();
        String owner = ((JLabel) list.getCellRenderer().getListCellRendererComponent(
                list, list.getModel().getElementAt(1), 1, false, false)).getText();

        assertEquals("General", general);
        assertEquals("[off] Owner", owner);
        assertFalse(owner.contains("@"));
    }

    @Test
    void enumBackedColumnsUseComboEditorsAndToolsUseCustomEditor() throws Exception {
        SessionHandlerPanel panel = panel(new SessionProfileRepository());
        JTable criteria = field(panel, "criteriaTable", JTable.class);
        JTable injection = field(panel, "injectionTable", JTable.class);
        JTable extraction = field(panel, "extractionTable", JTable.class);

        assertInstanceOf(DefaultCellEditor.class, criteria.getColumnModel().getColumn(0).getCellEditor());
        assertInstanceOf(DefaultCellEditor.class, injection.getColumnModel().getColumn(1).getCellEditor());
        assertInstanceOf(DefaultCellEditor.class, injection.getColumnModel().getColumn(4).getCellEditor());
        assertEquals("SessionVariableTemplateCellEditor",
                injection.getColumnModel().getColumn(3).getCellEditor().getClass().getSimpleName());
        assertInstanceOf(DefaultCellEditor.class, extraction.getColumnModel().getColumn(1).getCellEditor());
        assertInstanceOf(DefaultCellEditor.class, extraction.getColumnModel().getColumn(2).getCellEditor());
        assertInstanceOf(DefaultCellEditor.class, extraction.getColumnModel().getColumn(4).getCellEditor());
        assertEquals("ToolSourceCellEditor",
                injection.getColumnModel().getColumn(6).getCellEditor().getClass().getSimpleName());
        assertEquals("ToolSourceCellEditor",
                extraction.getColumnModel().getColumn(8).getCellEditor().getClass().getSimpleName());
    }

    @Test
    void burpTargetScopeDisablesCriteriaEditingAndCustomScopeEnablesIt() throws Exception {
        SessionHandlerPanel panel = panel(new SessionProfileRepository());
        JTable criteria = field(panel, "criteriaTable", JTable.class);
        JButton addCriterion = field(panel, "addCriterionButton", JButton.class);
        JButton removeCriterion = field(panel, "removeCriterionButton", JButton.class);
        JComboBox<?> scopeMode = field(panel, "scopeModeCombo", JComboBox.class);

        assertFalse(criteria.isEnabled());
        assertFalse(addCriterion.isEnabled());
        assertFalse(removeCriterion.isEnabled());

        runOnEdt(() -> scopeMode.setSelectedItem(SessionScopeMode.CUSTOM));

        assertTrue(criteria.isEnabled());
        assertTrue(addCriterion.isEnabled());
        assertTrue(removeCriterion.isEnabled());
    }

    @Test
    void toolSourceHelpersPreserveAllToolsAndExplicitSelections() {
        assertEquals("All tools", SessionHandlerPanel.displayTools(""));
        assertEquals(List.of(), SessionHandlerPanel.splitTools(""));
        assertEquals(List.of("PROXY", "REPEATER"), SessionHandlerPanel.splitTools("PROXY, REPEATER,PROXY"));
        assertEquals("PROXY,REPEATER", SessionHandlerPanel.formatTools(List.of("PROXY", "REPEATER")));
    }

    @Test
    void sessionInjectionVariableChoicesComeFromEnabledExtractors() {
        javax.swing.table.DefaultTableModel extractionModel = new javax.swing.table.DefaultTableModel(
                new Object[][]{
                        {true, "RESPONSE", "HEADER", "X-Token", "BODY", 0, "accessToken", "", ""},
                        {true, "RESPONSE", "HEADER", "X-Csrf", "BODY", 0, "session.csrf", "", ""},
                        {false, "RESPONSE", "HEADER", "X-Old", "BODY", 0, "oldToken", "", ""}
                },
                new Object[]{"Enabled", "Source", "Extractor", "Selector", "Regex Input", "Group",
                        "Target Key", "Transforms", "Tools"});

        assertEquals(
                List.of("{{session.accessToken}}", "{{session.csrf}}"),
                SessionHandlerPanel.availableSessionVariableTemplates(extractionModel));
    }

    @Test
    void linkedSessionInjectionChoicesUseAuthNamespaceAndIncludeProfileSuggestions() {
        javax.swing.table.DefaultTableModel extractionModel = new javax.swing.table.DefaultTableModel(
                new Object[][]{
                        {true, "RESPONSE", "HEADER", "X-Token", "BODY", 0, "accessToken", "", ""},
                        {false, "RESPONSE", "HEADER", "X-Old", "BODY", 0, "oldToken", "", ""}
                },
                new Object[]{"Enabled", "Source", "Extractor", "Selector", "Regex Input", "Group",
                        "Target Key", "Transforms", "Tools"});

        assertEquals(
                List.of("{{auth.csrfToken}}", "{{auth.accessToken}}"),
                SessionHandlerPanel.availableSessionVariableTemplates(
                        extractionModel, List.of("{{auth.csrfToken}}"), "auth"));
    }

    @Test
    void profileNameAutoSavesWithoutSaveButton() throws Exception {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(profile("p1", "Owner", true));
        SessionHandlerPanel panel = panel(repository);
        JList<?> profileList = field(panel, "profileList", JList.class);
        JTextField nameField = field(panel, "nameField", JTextField.class);

        runOnEdt(() -> profileList.setSelectedIndex(1));
        runOnEdt(() -> {
            nameField.setText("Renamed Owner");
            nameField.postActionEvent();
        });

        assertEquals("Renamed Owner", repository.profile("p1").orElseThrow().name());
    }

    @Test
    void externalExtractionRefreshesSelectedStateTableFromBackgroundThread() throws Exception {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(generalWithRules());
        SessionManager manager = manager(repository);
        SessionHandlerPanel panel = panel(manager);

        Thread worker = new Thread(() -> manager.applyExtractionRules(
                repository.profile(SessionProfile.GENERAL_ID).orElseThrow(),
                context(),
                requestSnapshot(),
                responseSnapshot("live-token")));
        worker.start();
        worker.join();
        runOnEdt(() -> {
        });

        JTable stateTable = field(panel, "stateTable", JTable.class);
        JCheckBox revealStateCheck = field(panel, "revealStateCheck", JCheckBox.class);
        assertEquals(1, stateTable.getRowCount());
        assertEquals("accessToken", stateTable.getValueAt(0, 0));
        assertEquals("********", stateTable.getValueAt(0, 1));
        assertTrue(stateTable.getValueAt(0, 2).toString()
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} .+"));
        assertFalse(stateTable.getValueAt(0, 2).toString().matches("\\d+"));

        runOnEdt(revealStateCheck::doClick);

        assertEquals("live-token", stateTable.getValueAt(0, 1));
    }

    @Test
    void stateUpdatedTimestampFormatsAsDateTimeString() {
        assertEquals("", SessionHandlerPanel.formatStateUpdatedAt(0L));
        String formatted = SessionHandlerPanel.formatStateUpdatedAt(1000L);

        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} .+"));
        assertFalse(formatted.matches("\\d+"));
    }

    @Test
    void runAuthNowKeepsVisibleResultAfterBackgroundRefresh() throws Exception {
        SessionHandlerPanel panel = panel(new SessionProfileRepository());
        JButton runAuthButton = field(panel, "runAuthButton", JButton.class);
        JLabel statusLabel = field(panel, "statusLabel", JLabel.class);

        runOnEdt(runAuthButton::doClick);
        waitForStatus(runAuthButton, statusLabel, "Authentication not run:");

        assertTrue(runAuthButton.isEnabled());
        assertTrue(statusLabel.getText().contains("No linked Auth Profile"));
        assertEquals(statusLabel.getText(), statusLabel.getToolTipText());
    }

    @Test
    void externalStateRefreshDoesNotReloadEditableConfigurationFields() throws Exception {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(generalWithRules());
        SessionManager manager = manager(repository);
        SessionHandlerPanel panel = panel(manager);
        JTextField ttlField = field(panel, "ttlField", JTextField.class);
        JTable injectionTable = field(panel, "injectionTable", JTable.class);

        runOnEdt(() -> ttlField.setText("999"));

        manager.applyExtractionRules(
                repository.profile(SessionProfile.GENERAL_ID).orElseThrow(),
                context(),
                requestSnapshot(),
                responseSnapshot("live-token"));
        runOnEdt(() -> {
        });

        JTable stateTable = field(panel, "stateTable", JTable.class);
        assertEquals("999", ttlField.getText());
        assertEquals(1, injectionTable.getRowCount());
        assertEquals(1, stateTable.getRowCount());
    }

    private static SessionHandlerPanel panel(SessionProfileRepository repository) throws Exception {
        return panel(manager(repository));
    }

    private static SessionHandlerPanel panel(SessionManager manager) throws Exception {
        AtomicReference<SessionHandlerPanel> panel = new AtomicReference<>();
        runOnEdt(() -> panel.set(new SessionHandlerPanel(manager, List::of)));
        return panel.get();
    }

    private static SessionManager manager(SessionProfileRepository repository) {
        ExtractionService extractionService = new ExtractionService();
        InjectionService injectionService = new InjectionService();
        return new SessionManager(
                repository,
                List::of,
                new FlowEngine(extractionService, injectionService, new NoopHttpClient()),
                extractionService,
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC));
    }

    private static SessionProfile profile(String id, String name, boolean enabled) {
        return new SessionProfile(
                id,
                name,
                enabled,
                false,
                SessionScope.burpTargetScope(),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
    }

    private static SessionProfile generalWithRules() {
        return SessionProfile.general().withConfig(
                "General",
                SessionScope.burpTargetScope(),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                List.of(new SessionInjectionRule(
                        "inject-1",
                        true,
                        InjectRule.Target.REQUEST_HEADER,
                        "Authorization",
                        "Bearer {{session.accessToken}}",
                        InjectRule.WriteStrategy.OVERWRITE,
                        List.of())),
                List.of(new SessionExtractionRule(
                        "extract-1",
                        true,
                        ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER,
                        "X-Token",
                        "accessToken",
                        List.of("PROXY"))));
    }

    @Test
    void transformColumnsRoundTripWithoutDroppingIntentionalTrailingSpaces() {
        List<String> transforms = List.of("trim", "stripPrefix:Bearer", "prefix:Token ");

        String displayed = SessionHandlerPanel.formatTransforms(transforms);

        assertEquals(transforms, SessionHandlerPanel.splitTransforms(displayed));
    }

    private static SessionTrafficContext context() {
        return new SessionTrafficContext("PROXY", true, "GET", "api.example", "/api", List.of(), "");
    }

    private static HttpMessageSnapshot requestSnapshot() {
        return new HttpMessageSnapshot("GET /api HTTP/1.1", List.of("Host: api.example"), "");
    }

    private static HttpMessageSnapshot responseSnapshot(String token) {
        return new HttpMessageSnapshot("HTTP/1.1 200", List.of("X-Token: " + token), "");
    }

    private static void runOnEdt(ThrowingRunnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                runnable.run();
            } catch (Exception exception) {
                error.set(exception);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
    }

    private static void waitForStatus(JButton button, JLabel label, String expectedPrefix) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            runOnEdt(() -> {
            });
            if (button.isEnabled() && label.getText().startsWith(expectedPrefix)) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for auth result; current status: " + label.getText());
    }

    private static <T> T field(Object target, String fieldName, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class NoopHttpClient implements HttpClient {
        @Override
        public HttpResponse send(HttpRequest request) {
            return (HttpResponse) Proxy.newProxyInstance(
                    HttpResponse.class.getClassLoader(),
                    new Class<?>[]{HttpResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "statusCode" -> (short) 200;
                        case "bodyToString" -> "";
                        case "toString" -> "NoopResponse";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
