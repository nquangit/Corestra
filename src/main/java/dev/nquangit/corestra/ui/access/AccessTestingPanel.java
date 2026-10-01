package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.validation.AccessValidationIssue;
import dev.nquangit.corestra.access.validation.AccessValidationReport;
import dev.nquangit.corestra.access.validation.AccessValidationSeverity;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Objects;

/**
 * Root panel for the Access Testing workspace.
 */
public final class AccessTestingPanel extends JPanel {
    private final JTabbedPane tabs;
    private final AccessUiController controller;
    private final Timer lockTimer;
    private Runnable authenticationNavigator = () -> { };

    /**
     * Creates the Access Testing panel.
     *
     * @param controller access UI controller
     */
    public AccessTestingPanel(AccessUiController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");

        this.tabs = new JTabbedPane(JTabbedPane.TOP);
        addAccessTab("Data Profiles", new DataProfilesPanel(controller),
                "Manage object-owner data values used by templates and matrix rows.");
        addAccessTab("Templates", new TemplatesPanel(controller),
                "Manage reusable target requests and preview scoped-variable rendering.");
        addAccessTab("Flow Targets", new FlowTargetsPanel(controller),
                "Manage saved Flowise flow snapshots for Access Testing Matrix rows.");
        addAccessTab("Evaluation Profiles", new EvaluationProfilesPanel(controller),
                "Configure response indicators, masking checks, and sensitive-field rules.");
        addAccessTab("Matrix", new MatrixPanel(controller),
                "Prepare disabled-by-default rows, setup, post-checks, teardown, and dry-run settings.");
        addAccessTab("Results", new ResultsPanel(controller),
                "Review run outcomes, redacted evidence, post-check summaries, and finding drafts.");
        addAccessTab("Guide", new AccessGuidePanel(),
                "Step-by-step setup checklist, auth flow model, and common injection recipes.");
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() instanceof AccessTabPanel accessTabPanel) {
                accessTabPanel.refreshUi();
            }
            updateLockState();
        });
        lockTimer = new Timer(500, event -> updateLockState());
        lockTimer.start();
        add(toolbar(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        updateLockState();
    }

    /**
     * Refreshes all access workspace tabs from the controller.
     */
    public void refreshUi() {
        updateLockState();
        for (int index = 0; index < tabs.getTabCount(); index++) {
            if (tabs.getComponentAt(index) instanceof AccessTabPanel accessTabPanel) {
                accessTabPanel.refreshUi();
            }
        }
    }

    /** Sets the root-tab navigation action used by the Manage Authentication button. */
    public void setAuthenticationNavigator(Runnable authenticationNavigator) {
        this.authenticationNavigator = Objects.requireNonNullElse(authenticationNavigator, () -> { });
    }

    private void updateLockState() {
        boolean running = controller.isPlanRunning();
        for (int index = 0; index < tabs.getTabCount(); index++) {
            String title = tabs.getTitleAt(index);
            boolean editableConfigTab = switch (title) {
                case "Data Profiles", "Templates",
                        "Flow Targets", "Evaluation Profiles" -> true;
                default -> false;
            };
            tabs.setEnabledAt(index, !running || !editableConfigTab);
        }
        if (running && !tabs.isEnabledAt(tabs.getSelectedIndex())) {
            selectTab("Matrix");
        }
    }

    private void addAccessTab(String title, JPanel panel, String tooltip) {
        tabs.addTab(title, panel);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, tooltip);
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton validate = StyledButton.neutral("Validate Access Project");
        validate.setToolTipText("Check references, placeholders, setup, post-checks, teardown, and export-safety warnings.");
        validate.addActionListener(event -> showValidationReport());
        JButton guide = StyledButton.neutral("Quick Guide");
        guide.setToolTipText("Open the Access Testing setup checklist and auth recipes.");
        guide.addActionListener(event -> selectTab("Guide"));
        JButton authentication = StyledButton.neutral("Manage Authentication…");
        authentication.setToolTipText("Open the shared Auth Providers and Auth Profiles workspace.");
        authentication.addActionListener(event -> authenticationNavigator.run());
        JLabel hint = new JLabel("Validate before dry-runs or live plans to catch broken references early.");
        hint.setToolTipText("Validation is read-only and never sends HTTP.");
        panel.add(validate);
        panel.add(guide);
        panel.add(authentication);
        panel.add(hint);
        return panel;
    }

    private void showValidationReport() {
        AccessValidationReport report = controller.validateAccessWorkspace();
        ValidationReportPanel panel = new ValidationReportPanel(report);
        int messageType = report.hasBlockingErrors() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE;
        JOptionPane.showMessageDialog(this, panel, "Access Project Validation", messageType);
    }

    private void selectTab(String title) {
        for (int index = 0; index < tabs.getTabCount(); index++) {
            if (title.equals(tabs.getTitleAt(index))) {
                tabs.setSelectedIndex(index);
                return;
            }
        }
    }

    private static final class ValidationReportPanel extends JPanel {
        private ValidationReportPanel(AccessValidationReport report) {
            super(new BorderLayout(8, 8));
            setPreferredSize(new Dimension(980, 420));

            DefaultTableModel model = new DefaultTableModel(
                    new Object[]{"Severity", "Category", "Title", "Object", "Message", "Suggested Fix"},
                    0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (AccessValidationIssue issue : report.issues()) {
                model.addRow(new Object[]{
                        issue.severity().name(),
                        issue.category().name(),
                        issue.title(),
                        relatedObject(issue),
                        issue.message(),
                        issue.suggestedFix()
                });
            }

            JTable table = new JTable(model);
            table.setFillsViewportHeight(true);
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
            table.setRowSorter(sorter);

            JComboBox<String> severityFilter = new JComboBox<>(new String[]{"All", "Errors", "Warnings", "Info"});
            severityFilter.setToolTipText("Filter validation issues by severity.");
            severityFilter.addActionListener(event ->
                    sorter.setRowFilter(filterFor((String) severityFilter.getSelectedItem())));

            JLabel summary = new JLabel("Errors: " + report.errorCount()
                    + "   Warnings: " + report.warningCount()
                    + "   Info: " + report.infoCount());
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            header.add(summary);
            header.add(new JLabel("Severity"));
            header.add(severityFilter);
            add(header, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(6, 6));
            if (report.issues().isEmpty()) {
                JLabel empty = new JLabel("No validation issues found in the current Access Testing workspace.");
                empty.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
                center.add(empty, BorderLayout.NORTH);
            }
            center.add(new JScrollPane(table), BorderLayout.CENTER);
            add(center, BorderLayout.CENTER);
        }

        private static String relatedObject(AccessValidationIssue issue) {
            if (issue.relatedObjectType().isBlank() && issue.relatedObjectId().isBlank()) {
                return "";
            }
            if (issue.relatedObjectType().isBlank()) {
                return issue.relatedObjectId();
            }
            if (issue.relatedObjectId().isBlank()) {
                return issue.relatedObjectType();
            }
            return issue.relatedObjectType() + " " + issue.relatedObjectId();
        }

        private static RowFilter<DefaultTableModel, Integer> filterFor(String selected) {
            if ("Errors".equals(selected)) {
                return RowFilter.regexFilter("^" + AccessValidationSeverity.ERROR.name() + "$", 0);
            }
            if ("Warnings".equals(selected)) {
                return RowFilter.regexFilter("^" + AccessValidationSeverity.WARNING.name() + "$", 0);
            }
            if ("Info".equals(selected)) {
                return RowFilter.regexFilter("^" + AccessValidationSeverity.INFO.name() + "$", 0);
            }
            return null;
        }
    }
}
