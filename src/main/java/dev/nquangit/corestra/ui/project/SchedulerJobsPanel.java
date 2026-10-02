package dev.nquangit.corestra.ui.project;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.session.SessionScheduledJob;
import dev.nquangit.corestra.session.SessionSchedulerSnapshot;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Shows runtime scheduler metadata; refreshes only while visible and never persists job history. */
public final class SchedulerJobsPanel extends JPanel implements AutoCloseable {
    private final Supplier<SessionSchedulerSnapshot> snapshots;
    private final Supplier<List<FlowDefinition>> flows;
    private final JobsTableModel model = new JobsTableModel();
    private final JTable table = new JTable(model) {
        @Override public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            int column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : getValueAt(row, column).toString();
        }
    };
    private final JLabel summary = new JLabel(" ");
    private final Timer refreshTimer;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a monitor backed by in-memory snapshots and the workspace's flow names. */
    public SchedulerJobsPanel(Supplier<SessionSchedulerSnapshot> snapshots, Supplier<List<FlowDefinition>> flows) {
        super(new BorderLayout(0, 8));
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.flows = Objects.requireNonNull(flows, "flows");
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Scheduler Jobs"),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(Math.max(24, table.getFont().getSize() + 10));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {145, 100, 190, 130, 65, 125, 125, 90, 155, 90};
        for (int column = 0; column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(900, 190));
        JLabel note = new JLabel("<html>Runtime only · Timings include waiting for authentication.<br>"
                + "Next run is an estimate; busy workers can delay it.</html>");
        note.setToolTipText("Last result belongs to this scheduler's last attempt. Manual and traffic-triggered auth can also move the next due time.");
        add(summary, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(note, BorderLayout.SOUTH);
        refreshTimer = new Timer(1000, event -> refreshJobs());
        refreshTimer.setCoalesce(true);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateVisibility();
        });
    }

    private void updateVisibility() {
        if (closed.get() || !isShowing()) {
            refreshTimer.stop();
        } else {
            refreshJobs();
            refreshTimer.start();
        }
    }

    void refreshJobs() {
        if (closed.get() || !isShowing()) return;
        try {
            SessionSchedulerSnapshot snapshot = snapshots.get();
            String selected = table.getSelectedRow() < 0 ? null
                    : model.jobs.get(table.convertRowIndexToModel(table.getSelectedRow())).profileId();
            model.update(snapshot, flows.get());
            if (selected != null) {
                for (int row = 0; row < model.jobs.size(); row++) {
                    if (model.jobs.get(row).profileId().equals(selected)) {
                        int view = table.convertRowIndexToView(row);
                        if (view >= 0) table.setRowSelectionInterval(view, view);
                        break;
                    }
                }
            }
            String state = snapshot.closed() ? "Stopped" : snapshot.started() ? "Active" : "Not started";
            summary.setText(state + "  ·  Workers: " + snapshot.activeWorkers() + " / " + snapshot.workerLimit()
                    + "  ·  Queued: " + snapshot.queuedJobs() + "  ·  Handlers: " + snapshot.jobs().size());
        } catch (RuntimeException ignored) {
            summary.setText("Scheduler status temporarily unavailable");
        }
    }

    /** Stops refresh when removed from the component tree. */
    @Override public void removeNotify() {
        refreshTimer.stop();
        super.removeNotify();
    }

    /** Stops UI refresh on extension unload, including callbacks already queued on the EDT. */
    @Override public void close() {
        closed.set(true);
        if (SwingUtilities.isEventDispatchThread()) refreshTimer.stop();
        else SwingUtilities.invokeLater(refreshTimer::stop);
    }

    static final class JobsTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Handler", "Actor", "Flow / Mode", "State", "Interval",
                "Last started", "Last finished", "Duration", "Next run (estimated)", "Last result"};
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        private List<SessionScheduledJob> jobs = List.of();
        private Map<String, String> flowNames = Map.of();
        private long now;

        void update(SessionSchedulerSnapshot snapshot, List<FlowDefinition> definitions) {
            jobs = snapshot.jobs();
            now = snapshot.capturedAtEpochMillis();
            flowNames = definitions.stream().collect(Collectors.toMap(FlowDefinition::id, FlowDefinition::name,
                    (first, second) -> first));
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return jobs.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int row, int column) {
            SessionScheduledJob job = jobs.get(row);
            return switch (column) {
                case 0 -> job.profileName();
                case 1 -> job.actorName();
                case 2 -> job.flowId().isBlank() ? job.mode()
                        : job.mode() + ": " + flowNames.getOrDefault(job.flowId(), job.flowId());
                case 3 -> status(job.status());
                case 4 -> job.intervalSeconds() + " s";
                case 5 -> time(job.lastStartedAtEpochMillis());
                case 6 -> time(job.lastCompletedAtEpochMillis());
                case 7 -> job.status() == SessionScheduledJob.Status.RUNNING
                        ? Math.max(0L, now - job.lastStartedAtEpochMillis()) + " ms (running)"
                        : job.lastCompletedAtEpochMillis() == 0L ? "—" : job.lastDurationMillis() + " ms";
                case 8 -> nextRun(job.nextRunAtEpochMillis());
                case 9 -> job.lastOutcome().isBlank() ? "—" : job.lastOutcome();
                default -> "";
            };
        }

        private String nextRun(long timestamp) {
            if (timestamp == 0L) return "—";
            if (timestamp <= now) return "Due";
            long seconds = (timestamp - now) / 1000 + ((timestamp - now) % 1000 == 0 ? 0 : 1);
            return "In " + seconds + " s · " + time(timestamp);
        }

        private static String time(long timestamp) {
            return timestamp == 0L ? "—" : TIME.format(Instant.ofEpochMilli(timestamp));
        }

        private static String status(SessionScheduledJob.Status status) {
            return switch (status) {
                case NOT_STARTED -> "Not started";
                case STOPPED -> "Stopped";
                case DISABLED -> "Disabled";
                case NO_AUTH -> "No auth configured";
                case WAITING -> "Waiting for schedule";
                case WAITING_WORKER -> "Due / waiting for worker";
                case QUEUED -> "Queued";
                case RUNNING -> "Running";
                case BACKOFF -> "Retry / backoff";
            };
        }
    }
}
