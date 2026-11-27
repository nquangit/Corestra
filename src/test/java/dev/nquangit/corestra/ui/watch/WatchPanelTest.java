package dev.nquangit.corestra.ui.watch;

import dev.nquangit.corestra.watch.WatchConfigFixtures;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchExpectation;
import dev.nquangit.corestra.watch.WatchRepository;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchPanelTest {

    @BeforeEach
    void requireDisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    private static void onEdt(Runnable runnable) throws Exception {
        SwingUtilities.invokeAndWait(runnable);
    }

    @Test
    void editsAreSavedThroughTheModelAndInvalidEditsAreRejected() throws Exception {
        WatchRepository repository = new WatchRepository();
        repository.save(List.of(WatchConfigFixtures.fullWatch("w1")));
        AtomicReference<WatchPanel> panel = new AtomicReference<>();
        onEdt(() -> panel.set(new WatchPanel(repository, null, definition -> null)));

        onEdt(() -> {
            assertEquals("w1", panel.get().currentDefinition().id());
            panel.get().nameField().setText("Renamed");
            panel.get().expectationsModel().add(new WatchExpectation("e2", true, "", WatchExpectation.Type.DELTA_EQUALS, "(", "", ""));
            invokeSave(panel.get());
            assertTrue(panel.get().messageText().contains("Not saved"), panel.get().messageText());
            assertEquals("Transfer w1", repository.load().getFirst().name());

            panel.get().expectationsModel().remove(1);
            invokeSave(panel.get());
            assertEquals("Renamed", repository.load().getFirst().name());
            assertEquals(WatchConfigFixtures.fullWatch("w1").observations(), repository.load().getFirst().observations());
        });
        onEdt(() -> panel.get().close());
    }

    @Test
    void showResultFromBackgroundThreadAppearsInRecentResults() throws Exception {
        WatchRepository repository = new WatchRepository();
        AtomicReference<WatchPanel> panel = new AtomicReference<>();
        onEdt(() -> panel.set(new WatchPanel(repository, null, null)));
        WatchSnapshot snapshot = new WatchSnapshot(1, 200, Map.of(), List.of());
        WatchResult result = new WatchResult("w", "Transfer", WatchResult.Status.PASS,
                List.of(new WatchResult.Row("balance[1]", "10", "5", new BigDecimal("-5"), true, WatchResult.Verdict.OK, "")),
                true, 0, snapshot, snapshot, List.of());

        Thread worker = new Thread(() -> panel.get().showResult(result));
        worker.start();
        worker.join();
        onEdt(() -> { });

        onEdt(() -> {
            assertEquals(1, panel.get().resultsTable().getRowCount());
            assertEquals(1, panel.get().detailModel().getRowCount());
            assertEquals("-5", panel.get().detailModel().getValueAt(0, 3));
            assertFalse(panel.get().testProbeButton().isEnabled());
            panel.get().close();
        });
    }

    @Test
    void enablingAnInvalidWatchFromTheListIsRefused() throws Exception {
        WatchRepository repository = new WatchRepository();
        repository.save(List.of(WatchDefinition.empty("draft", "Draft")));
        AtomicReference<WatchPanel> panel = new AtomicReference<>();
        onEdt(() -> panel.set(new WatchPanel(repository, null, null)));
        onEdt(() -> {
            panel.get().watchTable().setValueAt(Boolean.TRUE, 0, 0);
            assertFalse(repository.load().getFirst().enabled());
            assertTrue(panel.get().messageText().contains("Cannot enable"), panel.get().messageText());
            panel.get().close();
        });
    }

    private static void invokeSave(WatchPanel panel) {
        try {
            var method = WatchPanel.class.getDeclaredMethod("save");
            method.setAccessible(true);
            method.invoke(panel);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
