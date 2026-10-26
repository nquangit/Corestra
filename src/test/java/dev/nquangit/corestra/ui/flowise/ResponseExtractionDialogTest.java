package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ResponseExtractionDialogTest {
    @Test void selectedRowsBecomeRulesAndCancelLeavesConfigurationUntouched() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        var response = new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of("Set-Cookie: token=abc; HttpOnly", "Content-Type: application/json"), "{\"id\":7}");
        AtomicReference<List<ExtractRule>> saved = new AtomicReference<>();
        JDialog[] active = new JDialog[1];
        try {
            SwingUtilities.invokeLater(() -> ResponseExtractionDialog.open(new JPanel(), () -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); return response;
            }, List.of(), saved::set));
            await(() -> {
                active[0] = dialog(); return active[0] != null && find(active[0], JTable.class).getRowCount() > 0;
            });
            SwingUtilities.invokeAndWait(() -> {
                JTable table = find(active[0], JTable.class); var model = table.getModel();
                int cookie = -1;
                for (int row = 0; row < model.getRowCount(); row++) if ("COOKIE".equals(model.getValueAt(row, 1))) cookie = row;
                assertTrue(cookie >= 0); assertEquals("abc", model.getValueAt(cookie, 3));
                assertNull(saved.get());
                model.setValueAt(true, cookie, 0); model.setValueAt("cookieToken", cookie, 4);
                button(active[0], "Apply selected").doClick();
            });
            assertEquals(1, saved.get().size()); var rule = saved.get().getFirst();
            assertEquals("cookieToken", rule.targetVariableName()); assertEquals(ExtractRule.SourceSide.RESPONSE, rule.sourceSide());
            assertEquals("abc", new ExtractionService().extract(rule, null, response).value());
            saved.set(null);
            SwingUtilities.invokeLater(() -> ResponseExtractionDialog.open(new JPanel(), response, List.of(rule), saved::set));
            await(() -> { active[0] = dialog(); return active[0] != null; });
            SwingUtilities.invokeAndWait(() -> button(active[0], "Cancel").doClick());
            assertNull(saved.get());
        } finally { SwingUtilities.invokeAndWait(() -> { JDialog dialog = dialog(); if (dialog != null) dialog.dispose(); }); }
    }

    private static JDialog dialog() {
        for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isShowing() && d.getTitle().equals("Auto extract — response only")) return d;
        return null;
    }
    private static <T> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container c) for (Component child : c.getComponents()) { T found = find(child, type); if (found != null) return found; }
        return null;
    }
    private static JButton button(Component root, String name) {
        if (root instanceof JButton b && b.getText().equals(name)) return b;
        if (root instanceof Container c) for (Component child : c.getComponents()) { JButton found = button(child, name); if (found != null) return found; }
        return null;
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var result = new java.util.concurrent.atomic.AtomicBoolean(); SwingUtilities.invokeAndWait(() -> result.set(condition.getAsBoolean()));
            if (result.get()) return; Thread.sleep(20);
        }
        fail("Timed out waiting for extraction dialog");
    }
}
