package dev.nquangit.corestra.ui;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.ui.access.AccessTestingPanel;
import dev.nquangit.corestra.ui.access.AuthenticationPanel;
import dev.nquangit.corestra.ui.flowise.FlowisePanel;
import dev.nquangit.corestra.ui.project.ProjectPanel;
import dev.nquangit.corestra.ui.project.ParameterDictionaryPanel;
import dev.nquangit.corestra.ui.session.SessionHandlerPanel;
import dev.nquangit.corestra.ui.component.UiPolish;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.util.Objects;

/**
 * The root Swing panel registered as a Burp Suite tab for the Corestra extension.
 *
 * <p>Uses a {@link JTabbedPane} with {@link JTabbedPane#TOP} tab placement as the outer shell.
 * Authentication is a shared workspace used by Flowise, Session Handler, and Access Testing.</p>
 */
public final class CorestraRootPanel extends JPanel {
    private final JTabbedPane tabs;
    private JPanel manualTesting;

    /**
     * Creates the root panel and applies the active Burp theme.
     *
     * @param flowisePanel the Flowise editor panel to embed as the first tab
     * @param projectPanel the Project panel to embed as the final tab
     * @param sessionHandlerPanel the Session Handler panel
     * @param accessTestingPanel the Access Testing panel
     * @param api          the Montoya API instance used for theme application
     * @throws NullPointerException if any argument is {@code null}
     */
    public CorestraRootPanel(
            FlowisePanel flowisePanel,
            ProjectPanel projectPanel,
            SessionHandlerPanel sessionHandlerPanel,
            AccessTestingPanel accessTestingPanel,
            MontoyaApi api) {
        this(flowisePanel, projectPanel, sessionHandlerPanel, accessTestingPanel, null, api);
    }

    /** Creates the root panel with the optional runtime parameter dictionary tab. */
    public CorestraRootPanel(
            FlowisePanel flowisePanel,
            ProjectPanel projectPanel,
            SessionHandlerPanel sessionHandlerPanel,
            AccessTestingPanel accessTestingPanel,
            ParameterDictionaryPanel parameterDictionaryPanel,
            MontoyaApi api) {
        this(flowisePanel, projectPanel, null, sessionHandlerPanel, accessTestingPanel,
                parameterDictionaryPanel, api);
    }

    /** Creates the root panel with a shared Authentication tab. */
    public CorestraRootPanel(
            FlowisePanel flowisePanel,
            ProjectPanel projectPanel,
            AuthenticationPanel authenticationPanel,
            SessionHandlerPanel sessionHandlerPanel,
            AccessTestingPanel accessTestingPanel,
            ParameterDictionaryPanel parameterDictionaryPanel,
            MontoyaApi api) {
        super(new BorderLayout());
        Objects.requireNonNull(flowisePanel, "flowisePanel");
        Objects.requireNonNull(projectPanel, "projectPanel");
        Objects.requireNonNull(sessionHandlerPanel, "sessionHandlerPanel");
        Objects.requireNonNull(accessTestingPanel, "accessTestingPanel");
        Objects.requireNonNull(api, "api");

        tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.addTab("Flowise", flowisePanel);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, "Build and run reusable multi-step HTTP flows.");
        if (authenticationPanel != null) {
            tabs.addTab("Authentication", authenticationPanel);
            tabs.setToolTipTextAt(tabs.getTabCount() - 1, "Manage shared providers, actors, credentials, and auth state.");
        }
        tabs.addTab("Session Handler", sessionHandlerPanel);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, "Apply scoped authentication and session state to Burp traffic.");
        tabs.addTab("Access Testing", accessTestingPanel);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, "Build and execute access-control test matrices.");
        if (parameterDictionaryPanel != null) {
            tabs.addTab("Parameters", parameterDictionaryPanel);
            tabs.setToolTipTextAt(tabs.getTabCount() - 1, "Collect categorized wordlists, export files and rescan saved Proxy History.");
        }
        tabs.addTab("Project", projectPanel);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, "Project metadata, transport settings, import/export, and storage.");
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() == projectPanel) {
                projectPanel.refreshProjectDataUsage();
            }
            if (authenticationPanel != null && tabs.getSelectedComponent() == authenticationPanel) {
                authenticationPanel.refreshUi();
            }
            if (tabs.getSelectedComponent() == accessTestingPanel) {
                accessTestingPanel.refreshUi();
            }
        });
        if (authenticationPanel != null) {
            accessTestingPanel.setAuthenticationNavigator(
                    () -> tabs.setSelectedComponent(authenticationPanel));
        }
        add(tabs, BorderLayout.CENTER);

        ThemeCoordinator.applyTheme(this, api);
        UiPolish.apply(this);
    }
    /** Installs Manual Testing beside Flowise while keeping Project last. */
    public void installManualTesting(JPanel panel, MontoyaApi api) {
        if (manualTesting != null) throw new IllegalStateException("Manual workspace already installed");
        manualTesting = Objects.requireNonNull(panel);
        tabs.insertTab("Manual Testing", null, panel, "Edit independent request templates and test flows manually.", 1);
        ThemeCoordinator.applyTheme(panel, api); UiPolish.apply(panel);
    }

    /**
     * Installs the Watch configuration tab immediately before the last (Project) tab.
     *
     * @param panel Watch panel
     * @param api   Montoya API used for theming
     */
    public void installWatch(JPanel panel, MontoyaApi api) {
        Objects.requireNonNull(panel);
        tabs.insertTab("Watch", null, panel,
                "Before/after state checks around Repeater requests (e.g. balance after a transfer).",
                Math.max(0, tabs.getTabCount() - 1));
        ThemeCoordinator.applyTheme(panel, api); UiPolish.apply(panel);
    }

    /**
     * Installs the Global Retry tab immediately before the last (Project) tab.
     *
     * @param panel Global Retry panel
     * @param api   Montoya API used for theming
     */
    public void installGlobalRetry(JPanel panel, MontoyaApi api) {
        Objects.requireNonNull(panel);
        tabs.insertTab("Global Retry", null, panel,
                "Retry rate-limited / WAF-blocked responses on live Proxy, Repeater, Intruder and Scanner traffic.",
                Math.max(0, tabs.getTabCount() - 1));
        ThemeCoordinator.applyTheme(panel, api); UiPolish.apply(panel);
    }

    /** Navigates to the explicitly installed manual workspace. */
    public void showManualTesting() { if (manualTesting != null) tabs.setSelectedComponent(manualTesting); }

}
