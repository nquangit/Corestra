package dev.nquangit.corestra.ui;

import burp.api.montoya.MontoyaApi;

import java.awt.Component;
import java.awt.Font;
import java.util.Objects;

/**
 * Provides helper methods for applying the active Burp theme to Swing components.
 *
 * <p>Call {@link #applyTheme(Component, MontoyaApi)} on root panels and key child panels
 * to keep the extension UI visually aligned with Burp Suite's current look and feel.
 * Do not hardcode colors or fonts; instead obtain them from
 * {@link #currentFont(MontoyaApi)} or from {@code UIManager} defaults.</p>
 */
public final class ThemeCoordinator {

    private ThemeCoordinator() {
        // Utility class — not instantiable.
    }

    /**
     * Applies the active Burp theme to the supplied component and all of its descendants.
     *
     * <p>This delegates to {@code api.userInterface().applyThemeToComponent(component)},
     * which recursively applies Burp's current look-and-feel colours and fonts.</p>
     *
     * @param component the root Swing component to theme
     * @param api       the Montoya API instance for the running extension
     * @throws NullPointerException if either argument is {@code null}
     */
    public static void applyTheme(Component component, MontoyaApi api) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(api, "api");
        api.userInterface().applyThemeToComponent(component);
    }

    /**
     * Returns the font currently in use by the Burp Suite user interface.
     *
     * <p>Use this font when constructing labels or text components that must match
     * the Burp display font rather than the platform default.</p>
     *
     * @param api the Montoya API instance for the running extension
     * @return the current Burp display font
     * @throws NullPointerException if {@code api} is {@code null}
     */
    public static Font currentFont(MontoyaApi api) {
        Objects.requireNonNull(api, "api");
        return api.userInterface().currentDisplayFont();
    }
}
