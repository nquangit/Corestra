package dev.nquangit.corestra.ui.component;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;

/** Screen-bounded review content with wrapping guidance and a table; dialog buttons remain outside its scroll area. */
public final class TableReviewPanel extends JScrollPane {
    /** Creates review content sized for the owner's monitor, with a headless-safe fallback. */
    public TableReviewPanel(Component owner, String guidance, JTable table, int preferredWidth) {
        Dimension size = boundedSize(owner, preferredWidth, 430);
        JPanel content = new ReviewContent();
        content.add(new WrappedText(guidance, Math.max(1, size.width - 24)), BorderLayout.NORTH);
        JScrollPane rows = new JScrollPane(table);
        rows.setPreferredSize(new Dimension(Math.max(1, size.width - 24), 260));
        rows.getVerticalScrollBar().setUnitIncrement(16);
        content.add(rows, BorderLayout.CENTER);
        setViewportView(content);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_AS_NEEDED);
        setBorder(BorderFactory.createEmptyBorder());
        getVerticalScrollBar().setUnitIncrement(16);
        setPreferredSize(size);
        setMinimumSize(new Dimension(0, 0));
    }

    private static Dimension boundedSize(Component owner, int width, int height) {
        if (width <= 0) throw new IllegalArgumentException("preferredWidth must be positive");
        if (!GraphicsEnvironment.isHeadless()) {
            GraphicsConfiguration configuration = owner == null ? null : owner.getGraphicsConfiguration();
            if (configuration == null) configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            width = Math.min(width, Math.max(1, bounds.width - insets.left - insets.right - 120));
            height = Math.min(height, Math.max(1, bounds.height - insets.top - insets.bottom - 180));
        }
        return new Dimension(width, height);
    }

    private static final class ReviewContent extends JPanel implements Scrollable {
        private ReviewContent() { super(new BorderLayout(0, 8)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
