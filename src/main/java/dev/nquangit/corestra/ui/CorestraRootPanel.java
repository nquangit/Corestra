package dev.nquangit.corestra.ui;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.ui.access.AccessTestingPanel;
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
 * The first tab is the {@link FlowisePanel}. Additional tabs (Project, Access Testing, etc.) follow.</p>
 */
public final class CorestraRootPanel extends JPanel {

    /**
     * Creates the root panel and applies the active Burp theme.
     *
     * @param flowisePanel the Flowise editor panel to embed as the first tab
     * @param projectPanel the Project panel to embed as the second tab
     * @param sessionHandlerPanel the Session Handler panel to embed as the third tab
     * @param accessTestingPanel the Access Testing panel to embed as the fourth tab
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
        super(new BorderLayout());
        Objects.requireNonNull(flowisePanel, "flowisePanel");
        Objects.requireNonNull(projectPanel, "projectPanel");
        Objects.requireNonNull(sessionHandlerPanel, "sessionHandlerPanel");
        Objects.requireNonNull(accessTestingPanel, "accessTestingPanel");
        Objects.requireNonNull(api, "api");

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.addTab("Flowise", flowisePanel);
        tabs.addTab("Project", projectPanel);
        tabs.addTab("Session Handler", sessionHandlerPanel);
        tabs.addTab("Access Testing", accessTestingPanel);
        if (parameterDictionaryPanel != null) {
            tabs.addTab("Parameters", parameterDictionaryPanel);
        }
        add(tabs, BorderLayout.CENTER);

        ThemeCoordinator.applyTheme(this, api);
    }
}
