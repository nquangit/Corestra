package dev.nquangit.corestra.ui.component;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * A flat, pastel-style {@link JButton} that adapts its colour palette to Burp Suite's
 * active theme (light or dark) at paint time.
 *
 * <p>Each semantic variant carries a light-mode palette and a dark-mode palette.
 * The correct one is chosen dynamically via {@link UiTheme#isDark()} so the button
 * always looks correct regardless of which Burp theme the user has active.</p>
 *
 * <p>Use the static factory methods:</p>
 * <ul>
 *   <li>{@link #primary(String)} — indigo, primary / creation actions</li>
 *   <li>{@link #danger(String)}  — red, destructive actions</li>
 *   <li>{@link #success(String)} — green, run / confirm actions</li>
 *   <li>{@link #neutral(String)} — grey, secondary actions</li>
 * </ul>
 */
public final class StyledButton extends JButton {

    private record Palette(Color bg, Color hover, Color pressed, Color fg, Color border) {}

    private record Variant(Palette light, Palette dark) {}

    // ── Light-mode palettes (pastel bg, dark text) ───────────────────────────
    // ── Dark-mode palettes (deep-tinted bg, light text) ──────────────────────

    private static final Variant PRIMARY = new Variant(
            new Palette(new Color(0xEEF2FF), new Color(0xE0E7FF), new Color(0xC7D2FE),
                        new Color(0x3730A3), new Color(0xC7D2FE)),
            new Palette(new Color(0x1E1B4B), new Color(0x312E81), new Color(0x4338CA),
                        new Color(0xC7D2FE), new Color(0x4338CA)));

    private static final Variant DANGER = new Variant(
            new Palette(new Color(0xFEF2F2), new Color(0xFEE2E2), new Color(0xFECACA),
                        new Color(0x991B1B), new Color(0xFECACA)),
            new Palette(new Color(0x450A0A), new Color(0x7F1D1D), new Color(0x991B1B),
                        new Color(0xFCA5A5), new Color(0x991B1B)));

    private static final Variant SUCCESS = new Variant(
            new Palette(new Color(0xECFDF5), new Color(0xD1FAE5), new Color(0xA7F3D0),
                        new Color(0x065F46), new Color(0xA7F3D0)),
            new Palette(new Color(0x022C22), new Color(0x064E3B), new Color(0x065F46),
                        new Color(0x6EE7B7), new Color(0x047857)));

    private static final Variant NEUTRAL = new Variant(
            new Palette(new Color(0xF3F4F6), new Color(0xE5E7EB), new Color(0xD1D5DB),
                        new Color(0x374151), new Color(0xD1D5DB)),
            new Palette(new Color(0x1F2937), new Color(0x374151), new Color(0x4B5563),
                        new Color(0xD1D5DB), new Color(0x4B5563)));

    private static final int ARC = 6;

    private final Variant variant;

    private StyledButton(String text, Variant variant) {
        super(text);
        this.variant = variant;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setMargin(new Insets(3, 10, 3, 10));
    }

    /** Primary (indigo) — main creation actions such as «+ New Flow». */
    public static StyledButton primary(String text) { return new StyledButton(text, PRIMARY); }

    /** Danger (red) — destructive actions such as Delete. */
    public static StyledButton danger(String text)  { return new StyledButton(text, DANGER); }

    /** Success (green) — run / confirm actions. */
    public static StyledButton success(String text) { return new StyledButton(text, SUCCESS); }

    /** Neutral (grey) — secondary actions such as Rename or Send to Repeater. */
    public static StyledButton neutral(String text) { return new StyledButton(text, NEUTRAL); }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Resolved at paint time so the button responds to runtime theme changes. */
    private Palette palette() {
        // variant is null during super(text) → updateUI() → installDefaults() → getForeground()
        // because the JButton constructor fires before our field assignment runs.
        if (variant == null) return NEUTRAL.light();
        return UiTheme.isDark() ? variant.dark() : variant.light();
    }

    /** Expose theme-correct text colour so {@code super.paintComponent} uses it. */
    @Override
    public Color getForeground() {
        return palette().fg();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Palette p = palette();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color fill;
        if (!isEnabled()) {
            Color base = p.bg();
            fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 130);
        } else if (getModel().isPressed()) {
            fill = p.pressed();
        } else if (getModel().isRollover()) {
            fill = p.hover();
        } else {
            fill = p.bg();
        }

        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);

        g2.setColor(p.border());
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);

        g2.dispose();
        super.paintComponent(g);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width + 4, d.height + 2);
    }
}
