package dev.nquangit.corestra.ui;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.ui.access.AccessTestingPanel;
import dev.nquangit.corestra.ui.access.AuthenticationPanel;
import dev.nquangit.corestra.ui.flowise.FlowisePanel;
import dev.nquangit.corestra.ui.project.ProjectPanel;
import dev.nquangit.corestra.ui.project.ParameterDictionaryPanel;
import dev.nquangit.corestra.ui.session.SessionHandlerPanel;

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

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.addTab("Flowise", flowisePanel);
        if (authenticationPanel != null) {
            tabs.addTab("Authentication", authenticationPanel);
        }
        tabs.addTab("Session Handler", sessionHandlerPanel);
        tabs.addTab("Access Testing", accessTestingPanel);
        if (parameterDictionaryPanel != null) {
            tabs.addTab("Parameters", parameterDictionaryPanel);
        }
        tabs.addTab("Project", projectPanel);
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
    }
}
