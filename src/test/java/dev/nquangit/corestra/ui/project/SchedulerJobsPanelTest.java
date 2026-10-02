package dev.nquangit.corestra.ui.project;

import dev.nquangit.corestra.session.SessionScheduledJob;
import dev.nquangit.corestra.session.SessionSchedulerSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerJobsPanelTest {
    @Test
    void refreshesOnlyWhenShowingAndStopsAfterClose() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            SchedulerJobsPanel panel = new SchedulerJobsPanel(() -> {
                reads.incrementAndGet();
                return snapshot();
            }, List::of);
            JPanel host = new JPanel();
            host.add(panel);
            panel.refreshJobs();
            assertEquals(0, reads.get());
            try {
                host.addNotify();
                assertTrue(panel.isShowing());
                assertTrue(reads.get() > 0, "Showing the Project monitor should load immediately");
                host.setVisible(false);
                int hiddenReads = reads.get();
                panel.refreshJobs();
                assertEquals(hiddenReads, reads.get());
                host.setVisible(true);
                assertTrue(reads.get() > hiddenReads);
                panel.close();
                int closedReads = reads.get();
                panel.refreshJobs();
                assertEquals(closedReads, reads.get());
            } finally {
                panel.close();
                host.removeNotify();
            }
        });
    }

    @Test
    void rendersCountdownAndKeepsLastOutcomeSeparateFromCurrentStatus() {
        SchedulerJobsPanel.JobsTableModel model = new SchedulerJobsPanel.JobsTableModel();
        model.update(snapshot(), List.of());
        assertEquals(1, model.getRowCount());
        assertEquals("Retry / backoff", model.getValueAt(0, 3));
        assertTrue(model.getValueAt(0, 8).toString().startsWith("In 30 s"));
        assertEquals("SKIPPED", model.getValueAt(0, 9));
        assertFalse(model.isCellEditable(0, 0));
    }

    private static SessionSchedulerSnapshot snapshot() {
        return new SessionSchedulerSnapshot(1000, true, false, 0, 4, 0, List.of(
                new SessionScheduledJob("binding", "Alice handler", "Alice", "Keep-alive", "keep",
                        SessionScheduledJob.Status.BACKOFF, 10, 900, 1000, 100, 31000, "SKIPPED")));
    }
}
