package dev.nquangit.corestra.ui.component;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class StableSidebarSplitPaneTest {
    @Test void preservesUserWidthAcrossContentChangesWindowResizeAndTemporaryZeroSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel sidebar = new JPanel(new BorderLayout()), content = new JPanel(new BorderLayout());
            JLabel title = new JLabel("Requests"); sidebar.add(title);
            var split = new StableSidebarSplitPane(sidebar, content, 270);
            layout(split, 1000); assertEquals(270, sidebar.getWidth());
            title.setText("A request name and status that is deliberately much longer than the available sidebar space".repeat(4));
            var huge = new JTextArea("Long response data".repeat(1000)); huge.setMinimumSize(new Dimension(3000, 1500)); content.add(huge);
            layout(split, 1000); assertEquals(270, sidebar.getWidth());
            layout(split, 1300); assertEquals(270, sidebar.getWidth());
            split.setDividerLocation(340); layout(split, 1300); assertEquals(340, sidebar.getWidth());
            layout(split, 200); assertTrue(sidebar.getWidth() <= 200);
            layout(split, 1300); assertEquals(340, sidebar.getWidth());
            split.setSize(0, 0); split.doLayout(); layout(split, 1000); assertEquals(340, sidebar.getWidth());
            split.updateUI(); layout(split, 1000); assertEquals(340, sidebar.getWidth());
        });
    }
    private static void layout(JSplitPane split, int width) { split.setSize(width, 600); split.doLayout(); }
}
