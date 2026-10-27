package dev.nquangit.corestra.ui.component;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.text.View;
import java.awt.Dimension;
import java.awt.Insets;

/** Read-only plain text guidance that wraps to the available width without interpreting markup. */
public final class WrappedText extends JTextArea {
    private final int preferredWidth;

    /** Creates guidance with a moderate preferred width and native newline handling. */
    public WrappedText(String text) { this(text, 560); }

    /** Creates guidance with a bounded preferred width, while allowing narrower parent layouts. */
    public WrappedText(String text, int preferredWidth) {
        super(text);
        if (preferredWidth <= 0) throw new IllegalArgumentException("preferredWidth must be positive");
        this.preferredWidth = preferredWidth;
        setEditable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        setMargin(new Insets(0, 0, 0, 0));
        if (UIManager.getFont("Label.font") != null) setFont(UIManager.getFont("Label.font"));
        if (UIManager.getColor("Label.foreground") != null) setForeground(UIManager.getColor("Label.foreground"));
        setMinimumSize(new Dimension(0, 0));
    }

    /** Measures wrapped text at its actual layout width, including explicit newlines. */
    @Override public Dimension getPreferredSize() {
        if (getUI() == null) return super.getPreferredSize();
        int width = getWidth() > 0 ? getWidth() : Math.max(1, preferredWidth);
        Insets insets = getInsets();
        View view = getUI().getRootView(this);
        view.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
        return new Dimension(Math.max(1, preferredWidth),
                (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
    }

    /** Keeps BoxLayout help blocks at their content height instead of stretching vertically. */
    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
