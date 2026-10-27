package dev.nquangit.corestra.ui.component;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.*;

class PlainTextLayoutTest {
    @Test void guidanceWrapsAtRealWidthAndKeepsNewlinesAndMarkupLiteral() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String message = "First line\n" + "Long explanatory text with words to wrap. ".repeat(15)
                    + "\n<html>Literal input<br>must stay plain</html>";
            WrappedText text = new WrappedText(message, 560);
            assertEquals(message, text.getText());
            assertEquals(560, text.getPreferredSize().width);
            assertFalse(text.isEditable());
            assertEquals(3, text.getLineCount());
            text.setSize(560, 1000);
            int wideHeight = text.getPreferredSize().height;
            text.setSize(240, 1000);
            int narrowHeight = text.getPreferredSize().height;
            assertTrue(narrowHeight > wideHeight, "Narrow guidance must become taller, not force a wider window");
            assertEquals(0, text.getMinimumSize().width);
            text.setSize(240, narrowHeight);
            try {
                assertTrue(text.modelToView2D(text.getDocument().getLength()).getMaxY() <= narrowHeight + 1);
            } catch (javax.swing.text.BadLocationException e) { throw new AssertionError(e); }
        });
    }

    @Test void reviewKeepsWidthWithinViewportAndScrollsLongGuidanceInsteadOfGrowingTheDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var table = new JTable(new Object[][]{{false, "Rule", "X-Test"}}, new Object[]{"Apply", "Name", "Target"});
            var panel = new TableReviewPanel(null, "A sentence that needs to wrap. ".repeat(400), table, 780);
            Dimension preferred = panel.getPreferredSize();
            assertTrue(preferred.width <= 780); assertTrue(preferred.height <= 430);
            panel.setSize(320, 220);
            for (int pass = 0; pass < 4; pass++) layout(panel);
            assertEquals(panel.getViewport().getWidth(), panel.getViewport().getView().getWidth());
            assertFalse(panel.getHorizontalScrollBar().isVisible());
            assertTrue(panel.getVerticalScrollBar().isVisible());
            assertEquals(preferred, panel.getPreferredSize());
            assertTrue(panel.getViewport().getView().getPreferredSize().height > panel.getViewport().getHeight());
            assertEquals("Rule", table.getValueAt(0, 1));
        });
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container container) layout(container);
    }
}
