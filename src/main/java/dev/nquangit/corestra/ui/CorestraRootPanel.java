package dev.nquangit.corestra.ui;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.ui.flowise.FlowisePanel;
import dev.nquangit.corestra.ui.project.ProjectPanel;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Objects;

/**
 * The root Swing panel registered as a Burp Suite tab for the Corestra extension.
 *
 * <p>Uses a {@link JTabbedPane} with {@link JTabbedPane#LEFT} tab placement as the outer shell.
 * The first tab is the {@link FlowisePanel}. Future tabs (Settings, Run History, etc.) can be
 * added here without modifying lower-level panels.</p>
 */
public final class CorestraRootPanel extends JPanel {

    /**
     * Creates the root panel and applies the active Burp theme.
     *
     * @param flowisePanel the Flowise editor panel to embed as the first tab
     * @param projectPanel the Project panel to embed as the second tab
     * @param api          the Montoya API instance used for theme application
     * @throws NullPointerException if any argument is {@code null}
     */
    public CorestraRootPanel(FlowisePanel flowisePanel, ProjectPanel projectPanel, MontoyaApi api) {
        super(new BorderLayout());
        Objects.requireNonNull(flowisePanel, "flowisePanel");
        Objects.requireNonNull(projectPanel, "projectPanel");
        Objects.requireNonNull(api, "api");

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);
        addRotatedTab(tabs, "Flowise", flowisePanel);
        addRotatedTab(tabs, "Project", projectPanel);
        add(tabs, BorderLayout.CENTER);

        ThemeCoordinator.applyTheme(this, api);

        // Burp's applyThemeToComponent may reset custom tab components via updateUI().
        // Re-apply them explicitly so the rotated labels survive theme application.
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setTabComponentAt(i, new RotatedTabLabel(tabs.getTitleAt(i)));
        }
    }

    /**
     * Adds a tab to the supplied pane and installs a {@link RotatedTabLabel} as its tab
     * component so the title renders rotated 90° clockwise to save horizontal space.
     *
     * @param tabs    the target tabbed pane
     * @param title   the tab title
     * @param content the tab content component
     */
    static void addRotatedTab(JTabbedPane tabs, String title, Component content) {
        tabs.addTab(title, content);
        int index = tabs.getTabCount() - 1;
        tabs.setTabComponentAt(index, new RotatedTabLabel(title));
    }
}
