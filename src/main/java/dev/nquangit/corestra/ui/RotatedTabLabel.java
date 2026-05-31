package dev.nquangit.corestra.ui;

import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A {@link JLabel} that paints its text rotated 90° clockwise, suitable for use as a
 * custom tab component on a {@code JTabbedPane.LEFT} pane.
 *
 * <p>The component reports a preferred size with width and height transposed relative to
 * the natural label size, so the enclosing tab strip stays compact.</p>
 *
 * <p>Install via {@code JTabbedPane.setTabComponentAt(index, new RotatedTabLabel(title))}
 * after adding each tab.</p>
 */
final class RotatedTabLabel extends JLabel {

    /**
     * Creates a rotated tab label with the supplied title text.
     *
     * @param text the tab title
     */
    RotatedTabLabel(String text) {
        super(text);
        setOpaque(false);
    }

    /**
     * Returns a preferred size with width and height transposed so the label fits neatly
     * inside a left-side tab strip.
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension natural = super.getPreferredSize();
        // Swap: natural width becomes the component height, natural height becomes width.
        return new Dimension(natural.height + 4, natural.width + 8);
    }

    /**
     * Paints the text rotated 90° clockwise (reads top-to-bottom).
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            int w = getWidth();
            int h = getHeight();

            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(getText());

            // After translate(w,0) + rotate(PI/2):
            //   rotated +x  →  screen down
            //   rotated +y  →  screen left
            // Center text along height (screen vertical = rotated x axis):
            //   start at x = (h - textWidth) / 2
            // Center baseline along width (screen horizontal = rotated y axis):
            //   baseline at y = (w + fm.ascent - fm.descent) / 2
            int rx = (h - textWidth) / 2;
            int ry = (w + fm.getAscent() - fm.getDescent()) / 2;

            g2.translate(w, 0);
            g2.rotate(Math.PI / 2);

            g2.setFont(getFont());
            g2.setColor(getForeground() != null
                    ? getForeground()
                    : UIManager.getColor("Label.foreground"));
            g2.drawString(getText(), rx, ry);
        } finally {
            g2.dispose();
        }
    }
}
