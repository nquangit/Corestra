package dev.nquangit.corestra.ui.access;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;

/** Compact in-product guide for the Access Testing setup sequence and common auth recipes. */
final class AccessGuidePanel extends JPanel {
    AccessGuidePanel() {
        super(new BorderLayout());
        JTabbedPane topics = new JTabbedPane(JTabbedPane.LEFT);
        topics.addTab("Start", topic("""
                ACCESS TESTING CHECKLIST

                1. Build and test login/refresh/validate flows in Flowise.
                2. In each auth flow, extract reusable values with stable names:
                   accessToken, refreshToken, sessionId, csrfToken, expiresAt/expiresIn.
                3. Open Authentication and create one Auth Provider describing flow order
                   and target injection.
                4. In Authentication, create one Auth Profile per real actor. Bind all actors to the provider,
                   then enter each actor's own username/password/device data in Credentials.
                5. Create Data Profiles for objects owned by those actors.
                6. Create a target Template or Flow Target and Matrix rows.
                7. Validate, Dry Run, inspect the rendered actor request, then run live.

                Full examples: guides/ACCESS_TESTING.md and guides/AUTH_RECIPES.md
                """));
        topics.addTab("Auth flows", topic("""
                HOW CORESTRA CHOOSES THE RIGHT USER

                A Matrix row references exactly one Auth Profile. That profile supplies its own
                credentials and cached auth state. The provider is shared behavior; it does not
                contain a specific user's password or token.

                Auth flow variables available for Alice, for example:
                  credentials.username   credentials.password
                  auth.accessToken       auth.refreshToken
                  auth.cookie.sessionId  env.baseUrl

                Login/refresh Extract Rules produce unscoped Auth State keys such as
                accessToken and refreshToken. Provider templates consume those keys as
                {{auth.accessToken}} and {{auth.refreshToken}}.

                Extracted values are saved only into Alice's Auth Profile. Bob has a separate
                profile, lock, login execution, token, cookie, and expiry.

                Fallback TTL prevents a login flow from running once per target when an API does
                not return expiry data. Extracted expiresAt/expiresIn takes priority over TTL.
                """));
        topics.addTab("Recipes", topic("""
                COMMON INJECTION RECIPES

                Bearer:
                  HEADER / Authorization / Bearer {{auth.accessToken}} / OVERRIDE

                Cookie:
                  COOKIE / SESSION / {{auth.sessionId}} / OVERRIDE

                Bearer + CSRF:
                  HEADER / Authorization / Bearer {{auth.accessToken}}
                  COOKIE / SESSION / {{auth.sessionId}}
                  HEADER / X-CSRF-Token / {{auth.csrfToken}}

                Manual actor:
                  Put complete fixed headers and cookies in the Auth Profile. Choose Manual as
                  provider. Corestra will not execute login/refresh flows for that profile.
                """));
        topics.addTab("Verify", topic("""
                BEFORE A LIVE PLAN

                • Validate Access Project and resolve errors.
                • Dry Run / Render Only each actor at least once.
                • Confirm Authorization and Cookie values belong to the selected actor.
                • Confirm owner data and target actor are intentionally different for BOLA tests.
                • Add failure rules (usually 401/403) if target auth retry is enabled; they also drive live re-login for linked Session Handler profiles.
                • Start with one Matrix row, then expand to the full plan.

                Access requests use an actor-isolated transport. Project Session Handler rules
                are not allowed to overwrite the selected Access Testing actor.
                """));
        add(topics, BorderLayout.CENTER);
    }

    private static JScrollPane topic(String text) {
        JTextArea area = new JTextArea(text.strip());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        Font font = area.getFont();
        if (font != null) area.setFont(font.deriveFont(font.getSize2D() + 1f));
        area.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        return new JScrollPane(area);
    }
}
