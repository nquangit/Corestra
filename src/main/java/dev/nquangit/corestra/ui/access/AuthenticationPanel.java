package dev.nquangit.corestra.ui.access;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.util.Objects;

/**
 * Root workspace for authentication providers and actor profiles shared by Flowise,
 * Session Handler, and Access Testing.
 */
public final class AuthenticationPanel extends JPanel {
    private final AccessUiController controller;
    private final AuthProvidersPanel providersPanel;
    private final AuthProfilesPanel profilesPanel;
    private final JLabel statusLabel;
    private final Timer lockTimer;

    /** Creates the shared authentication workspace. */
    public AuthenticationPanel(AccessUiController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        providersPanel = new AuthProvidersPanel(controller);
        profilesPanel = new AuthProfilesPanel(controller);

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.addTab("Auth Providers", providersPanel);
        tabs.setToolTipTextAt(0,
                "Configure shared login, refresh, validation, injection, and retry behavior.");
        tabs.addTab("Auth Profiles", profilesPanel);
        tabs.setToolTipTextAt(1,
                "Manage shared actors, roles, credentials, and cached authentication state.");
        tabs.addChangeListener(event -> refreshSelectedTab(tabs));

        statusLabel = new JLabel("Shared by Flowise, Session Handler, and Access Testing.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        add(statusLabel, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        lockTimer = new Timer(500, event -> updateLockState());
        lockTimer.start();
        updateLockState();
    }

    /** Refreshes provider/profile data and the active-plan editing lock. */
    public void refreshUi() {
        providersPanel.refreshUi();
        profilesPanel.refreshUi();
        updateLockState();
    }

    private void refreshSelectedTab(JTabbedPane tabs) {
        if (tabs.getSelectedComponent() instanceof AccessTabPanel panel) {
            panel.refreshUi();
        }
        updateLockState();
    }

    private void updateLockState() {
        boolean locked = controller.isPlanRunning();
        providersPanel.setEditingEnabled(!locked);
        profilesPanel.setEditingEnabled(!locked);
        statusLabel.setText(locked
                ? "Authentication editing is locked while an Access Testing plan is running."
                : "Shared by Flowise, Session Handler, and Access Testing.");
    }
}
