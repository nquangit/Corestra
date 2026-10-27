package dev.nquangit.corestra.ui.component;

import javax.swing.BorderFactory;
import javax.swing.JSplitPane;
import java.awt.Component;
import java.awt.Dimension;

/** Keeps a user-sized sidebar independent of changing editor contents and temporary zero-sized tab layouts. */
public final class StableSidebarSplitPane extends JSplitPane {
    private static final int MIN_SIDEBAR_WIDTH = 160;
    private int sidebarWidth;
    private boolean initialized, layingOut;

    /** Creates a draggable sidebar whose width stays fixed while extra window space goes to the editor. */
    public StableSidebarSplitPane(Component sidebar, Component editor, int initialWidth) {
        super(JSplitPane.HORIZONTAL_SPLIT, sidebar, editor);
        sidebarWidth = Math.max(MIN_SIDEBAR_WIDTH, initialWidth); initialized = true;
        sidebar.setMinimumSize(new Dimension(MIN_SIDEBAR_WIDTH, 0));
        sidebar.setPreferredSize(new Dimension(sidebarWidth, 300)); editor.setMinimumSize(new Dimension(0, 0));
        setResizeWeight(0); setContinuousLayout(true); setDividerSize(5); setBorder(BorderFactory.createEmptyBorder());
        super.setDividerLocation(sidebarWidth);
    }

    /** Remembers explicit divider changes, without recording layout-driven clamping. */
    @Override public void setDividerLocation(int location) {
        if (initialized && !layingOut && location >= 0) { sidebarWidth = location == 0 ? 0 : Math.max(MIN_SIDEBAR_WIDTH, location); location = sidebarWidth; }
        super.setDividerLocation(location);
    }

    /** Restores the requested width after reparenting or constrained window sizes, without polling/timers. */
    @Override public void doLayout() {
        boolean previous = layingOut; layingOut = true;
        try {
            if (initialized && getWidth() > 0) {
                var insets = getInsets(); int available = Math.max(0, getWidth() - insets.left - insets.right - getDividerSize());
                int target = insets.left + Math.min(sidebarWidth, available);
                if (getDividerLocation() != target) super.setDividerLocation(target);
            }
            super.doLayout();
        } finally { layingOut = previous; }
    }
}
