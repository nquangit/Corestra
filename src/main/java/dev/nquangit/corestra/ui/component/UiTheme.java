package dev.nquangit.corestra.ui.component;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Lightweight helper for adapting UI colours to Burp Suite's active theme.
 *
 * <p>Calls to {@link #isDark()} are cheap (a single {@link UIManager} lookup) and safe to
 * make inside {@code paintComponent} or {@code getListCellRendererComponent} so the UI
 * responds correctly even if Burp's theme is changed at runtime.</p>
 */
public final class UiTheme {

    private UiTheme() {}

    /**
     * Returns {@code true} when the active Burp theme has a dark background.
     * Determined by the perceived luminance of {@code Panel.background}.
     */
    public static boolean isDark() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return false;
        // Perceived luminance (ITU-R BT.601)
        return (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) < 128;
    }

    /**
     * Returns {@code light} or {@code dark} depending on the active theme.
     *
     * @param light colour for light mode
     * @param dark  colour for dark mode
     * @return the appropriate colour for the current theme
     */
    public static Color pick(Color light, Color dark) {
        return isDark() ? dark : light;
    }

    /**
     * Blends {@code base} toward {@code tint} by {@code ratio} (0.0 = base, 1.0 = tint).
     */
    static Color blend(Color base, Color tint, float ratio) {
        float inv = 1f - ratio;
        return new Color(
                clamp((int)(base.getRed()   * inv + tint.getRed()   * ratio)),
                clamp((int)(base.getGreen() * inv + tint.getGreen() * ratio)),
                clamp((int)(base.getBlue()  * inv + tint.getBlue()  * ratio)));
    }

    /** Lightens a colour by adding {@code delta} to each channel. */
    static Color lighten(Color c, int delta) {
        return new Color(clamp(c.getRed() + delta),
                         clamp(c.getGreen() + delta),
                         clamp(c.getBlue() + delta));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
