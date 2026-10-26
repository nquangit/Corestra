package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.internal.*;
import dev.nquangit.corestra.flow.execution.*;
import dev.nquangit.corestra.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class FlowLogPanelTest {
    private MontoyaObjectFactory previous;
    private JFrame frame;
    private boolean initialized;
    @BeforeEach void setup() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        previous = ObjectFactoryLocator.FACTORY;
        initialized = true;
        ObjectFactoryLocator.FACTORY = dev.nquangit.corestra.integration.ManualTestMessages.factory();
        FlowisePerformanceTest.store = new java.util.HashMap<>();
        FlowisePerformanceTest.created = 0;
    }
    @AfterEach void cleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> { if (frame != null) frame.dispose(); });
        if (initialized) ObjectFactoryLocator.FACTORY = previous;
    }

    @Test void fullHeightHistoryFiltersByReadableRequestAndCanIncludeChildrenWithoutLosingSelection() throws Exception {
        FlowLogPanel[] panel = new FlowLogPanel[1]; FlowExecutionLog log = new FlowExecutionLog(); JTable[] table = new JTable[1];
        SwingUtilities.invokeAndWait(() -> {
            var persistence = FlowisePerformanceTest.persistence();
            var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
            var a = FlowisePerformanceTest.flow("A"); var b = FlowisePerformanceTest.flow("B"); controller.addFlow(a); controller.addFlow(b); controller.setActiveFlow(a);
            log.record(exchange("A", "step-A", "/parent")); log.record(exchange("B", "step-B", "/child"));
            panel[0] = new FlowLogPanel(controller, log, FlowisePerformanceTest.api(persistence));
            frame = new JFrame(); frame.setContentPane(panel[0]); frame.setSize(1150, 700); frame.setVisible(true);
            table[0] = find(panel[0], JTable.class);
        });
        await(() -> table[0].getRowCount() == 1);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("GET /parent HTTP/1.1", table[0].getValueAt(0, 2)); assertEquals("Request", table[0].getValueAt(0, 3));
            var all = find(panel[0], JCheckBox.class); all.doClick(); assertEquals(2, table[0].getRowCount());
            assertEquals("GET /child HTTP/1.1", table[0].getValueAt(0, 2)); table[0].setRowSelectionInterval(0, 0);
            var split = find(panel[0], JSplitPane.class); assertTrue(split.getBottomComponent().getHeight() > split.getTopComponent().getHeight());
            find(panel[0], JComboBox.class).setSelectedIndex(1);
            assertEquals(JSplitPane.VERTICAL_SPLIT, ((JSplitPane) split.getBottomComponent()).getOrientation());
            log.record(exchange("A", "step-A", "/new-parent"));
        });
        await(() -> table[0].getRowCount() == 3);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("GET /child HTTP/1.1", table[0].getValueAt(table[0].getSelectedRow(), 2));
            // The filter includes the real path, rather than requiring an opaque step ID.
            find(panel[0], JTextField.class).setText("new-parent");
            assertEquals(1, table[0].getRowCount()); assertEquals("GET /new-parent HTTP/1.1", table[0].getValueAt(0, 2));
            assertEquals(2, FlowisePerformanceTest.created);
        });
    }

    private static ExecutionResult exchange(String flow, String step, String path) {
        return ExecutionResult.success(flow, flow, List.of(new StepExecutionRecord(step,
                "GET " + path + " HTTP/1.1\r\nHost: api.test\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\nbody", 200, true, "", 1, 2, Map.of())));
    }
    private static <T> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container c) for (Component child : c.getComponents()) { T result = find(child, type); if (result != null) return result; }
        return null;
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var result = new java.util.concurrent.atomic.AtomicBoolean(); SwingUtilities.invokeAndWait(() -> result.set(condition.getAsBoolean()));
            if (result.get()) return; Thread.sleep(20);
        }
        fail("Timed out waiting for log rows");
    }
}
