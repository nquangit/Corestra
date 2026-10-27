package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.dependency.SetupSuggestionResult;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.component.UiTheme;
import dev.nquangit.corestra.manual.ManualRunProgress;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Displays the ordered list of {@link FlowStep} instances for the active flow.
 *
 * <p>Each step is a plain {@link JPanel} row — no {@code JList} renderer pipeline.
 * Drag-and-drop reordering is implemented via mouse listeners and a painted drop
 * indicator line.
 */
public final class StepListPanel extends JPanel {

    private final FlowWorkspaceController controller;
    private final AccessUiController accessController;
    private final Presentation presentation;
    private final JPanel listContainer;

    private List<FlowStep> renderedSteps;
    private boolean renderedDark;
    private Color renderedBackground;
    private boolean executionProgressEnabled;
    private String executionFlowId;
    private Map<String, ManualRunProgress.Status> executionStatuses = Map.of(), renderedStatuses = Map.of();
    // Last rendered selection; only refresh() updates it from the controller.
    private String  selectedStepId  = null;
    private String  draggedStepId   = null;
    private int     dropTargetIndex = -1;
    private int     dropIndicatorY  = -1;

    /** Chooses roomier Flowise rows or the existing compact manual-testing layout. */
    public enum Presentation { COMPACT, FLOWISE }

    public StepListPanel(FlowWorkspaceController controller) {
        this(controller, null);
    }

    /**
     * Creates a step list with optional Access Testing workflow actions.
     *
     * @param controller flow workspace controller
     * @param accessController optional Access Testing controller
     */
    public StepListPanel(FlowWorkspaceController controller, AccessUiController accessController) {
        this(controller, accessController, Presentation.COMPACT);
    }

    /** Creates a request list with an explicit workspace presentation, without changing request data. */
    public StepListPanel(FlowWorkspaceController controller, AccessUiController accessController, Presentation presentation) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.accessController = accessController;
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        setMinimumSize(new java.awt.Dimension(160, 0));
        setPreferredSize(new java.awt.Dimension(presentation == Presentation.FLOWISE ? 320 : 260, 300));

        listContainer = new JPanel() {
            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                // Draw drag-drop indicator line while dragging.
                if (draggedStepId != null && dropIndicatorY >= 0) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(0x3B82F6));
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawLine(8, dropIndicatorY, getWidth() - 8, dropIndicatorY);
                    // Arrow nubs on each end
                    g2.fillOval(4, dropIndicatorY - 3, 6, 6);
                    g2.fillOval(getWidth() - 10, dropIndicatorY - 3, 6, 6);
                    g2.dispose();
                }
            }
        };
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(true);

        JScrollPane scroll = new JScrollPane(listContainer);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(true);

        add(scroll, BorderLayout.CENTER);

        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh
    // ─────────────────────────────────────────────────────────────────────────

    /** Applies transient progress to this workspace only; no controller updates or persistence writes occur. Call on the EDT. */
    public void setExecutionStatuses(String flowId, Map<String, ManualRunProgress.Status> statuses) {
        Map<String, ManualRunProgress.Status> previous = executionStatuses;
        boolean first = !executionProgressEnabled; executionProgressEnabled = true; executionFlowId = flowId;
        executionStatuses = Map.copyOf(statuses); if (first) renderedSteps = null;
        refresh();
        var active = controller.activeFlow();
        if (active == null || !active.id().equals(flowId)) return;
        for (int i = 0; i < active.steps().size(); i++) {
            var s = executionStatuses.get(active.steps().get(i).id());
            if (s != null && s.state() == ManualRunProgress.State.RUNNING && !s.equals(previous.get(active.steps().get(i).id()))) {
                JPanel row = (JPanel) listContainer.getComponent(i);
                row.scrollRectToVisible(new Rectangle(0, 0, row.getWidth(), row.getHeight()));
                break;
            }
        }
    }

    private void refresh() {
        if (draggedStepId != null) return; // don't interrupt an active drag
        FlowDefinition active = controller.activeFlow();
        List<FlowStep> steps = (active == null) ? List.of() : active.steps();
        FlowStep controllerSelection = controller.selectedStep();
        String previousSelection = selectedStepId;
        selectedStepId = controllerSelection != null
                && steps.stream().anyMatch(step -> step.id().equals(controllerSelection.id()))
                ? controllerSelection.id()
                : null;

        boolean dark = UiTheme.isDark();

        Color listBg = UIManager.getColor("List.background");
        if (listBg == null) listBg = dark ? new Color(0x1A2030) : new Color(0xF8FAFC);
        if (steps.equals(renderedSteps) && dark == renderedDark && Objects.equals(listBg, renderedBackground)) {
            boolean selectionChanged = !Objects.equals(previousSelection, selectedStepId);
            if (!selectionChanged && renderedStatuses.equals(executionStatuses)) return;
            for (int i = 0; i < steps.size(); i++) {
                String id = steps.get(i).id();
                if ((selectionChanged && (id.equals(previousSelection) || id.equals(selectedStepId)))
                        || !Objects.equals(renderedStatuses.get(id), executionStatuses.get(id))) {
                    listContainer.remove(i);
                    listContainer.add(buildRow(steps.get(i), i, dark, listBg), i);
                }
            }
            renderedStatuses = executionStatuses;
            listContainer.revalidate();
            listContainer.repaint();
            return;
        }
        renderedSteps = steps;
        renderedDark = dark;
        renderedBackground = listBg;
        renderedStatuses = executionStatuses;
        listContainer.removeAll();
        listContainer.setBackground(listBg);

        for (int i = 0; i < steps.size(); i++) {
            listContainer.add(buildRow(steps.get(i), i, dark, listBg));
        }
        listContainer.add(Box.createVerticalGlue());
        listContainer.revalidate();
        listContainer.repaint();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row builder
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildRow(FlowStep step, int index, boolean dark, Color listBg) {
        boolean selected = step.id().equals(selectedStepId);
        boolean roomy = presentation == Presentation.FLOWISE;

        // ── Colours ──────────────────────────────────────────────────────────
        Color sep = dark ? new Color(0x1E293B) : new Color(0xE9EEF5);

        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (selected) {
                    // Keep selection visible when the editor owns focus and across progress updates.
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(dark ? new Color(0x79B8FF) : new Color(0x2563BE));
                    g2.fillRect(0, 0, 4, getHeight());
                    g2.drawRect(0, 0, getWidth() - 1, getHeight() - 2);
                    g2.dispose();
                }
            }
        };

        row.setBackground(listBg);
        ManualRunProgress.Status progress = executionProgressEnabled
                ? (controller.activeFlow() != null && Objects.equals(executionFlowId, controller.activeFlow().id())
                    ? executionStatuses.getOrDefault(step.id(), ManualRunProgress.Status.initial(step.enabled()))
                    : ManualRunProgress.Status.initial(step.enabled())) : null;
        if (progress != null) {
            Color tint = switch (progress.state()) {
                case RUNNING -> dark ? new Color(0x3F340F) : new Color(0xFFF5CC);
                case COMPLETED -> dark ? new Color(0x103321) : new Color(0xEAF8EE);
                case FAILED -> dark ? new Color(0x3D2028) : new Color(0xFDEDEF);
                default -> dark ? new Color(0x242A34) : new Color(0xF1F3F5);
            };
            row.setBackground(tint); row.putClientProperty("executionState", progress.state());
            row.setToolTipText(progressTooltip(progress));
        }
        // Progress tints describe unselected rows; selection must always take precedence.
        if (selected) row.setBackground(dark ? new Color(0x173D66) : new Color(0xD0E3FF));
        row.setOpaque(true);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, sep),
                BorderFactory.createEmptyBorder(roomy ? 9 : 6, roomy ? 10 : 8, roomy ? 9 : 6, roomy ? 9 : 7)));
        Font base = UIManager.getFont("Label.font");
        int height = Math.max(roomy ? 54 : 44, row.getFontMetrics(base == null ? row.getFont() : base).getHeight() * 2 + (roomy ? 20 : 12));
        row.setMinimumSize(new java.awt.Dimension(0, height));
        row.setPreferredSize(new java.awt.Dimension(roomy ? 320 : 260, height));
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, height));

        // ── Enable / disable checkbox ─────────────────────────────────────────
        JCheckBox enableBox = new JCheckBox();
        enableBox.setSelected(step.enabled());
        enableBox.setOpaque(false);
        enableBox.setFocusPainted(false);
        enableBox.setToolTipText(step.enabled() ? "Step enabled — click to disable" : "Step disabled — click to enable");
        enableBox.addActionListener(e -> toggleStepEnabled(step, enableBox.isSelected()));

        // ── Method badge ──────────────────────────────────────────────────────
        StoredRequestTemplate tmpl = step.requestTemplate();
        String method = tmpl != null && tmpl.method() != null ? tmpl.method().toUpperCase() : "GET";
        String endpoint = tmpl == null ? "" : tmpl.scheme() + "://" + tmpl.host() + ":" + tmpl.port() + tmpl.pathAndQuery();
        String tooltip = step.name() + " · " + method + " " + endpoint + (progress == null ? "" : " · " + progressTooltip(progress));
        row.setToolTipText(tooltip);
        JLabel badge = buildMethodBadge(method.length() > 10 ? method.substring(0, 9) + "…" : method, dark, base);
        if (roomy) {
            if (base != null) badge.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - .5f));
            badge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            badge.setMaximumSize(badge.getPreferredSize());
            badge.setName("flowiseMethodBadge");
        }
        badge.setToolTipText(method + " " + endpoint);

        // ── Text block ────────────────────────────────────────────────────────
        Color nameFg = step.enabled()
                        ? (dark ? new Color(0xCDD5E0) : new Color(0x1E293B))
                        : (dark ? new Color(0x64748B) : new Color(0x8491A2));
        if (selected) nameFg = dark ? new Color(0xF1F7FF) : new Color(0x123B68);
        JLabel nameLabel = new JLabel(step.name());
        nameLabel.setForeground(nameFg);
        if (base != null) nameLabel.setFont(base.deriveFont(selected ? Font.BOLD : Font.PLAIN, base.getSize2D() + (roomy ? 1 : 0)));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        nameLabel.setToolTipText(tooltip);
        nameLabel.setMinimumSize(new java.awt.Dimension(0, nameLabel.getPreferredSize().height));
        JPanel textBlock = new JPanel(new BorderLayout(0, roomy ? 2 : 1));
        textBlock.setOpaque(false);
        textBlock.setMinimumSize(new java.awt.Dimension(0, 0));
        if (progress == null) {
            textBlock.add(nameLabel, BorderLayout.NORTH);
        } else {
            JPanel title = new JPanel(new BorderLayout(6, 0));
            title.setOpaque(false);
            title.setMinimumSize(new java.awt.Dimension(0, nameLabel.getPreferredSize().height));
            title.add(nameLabel, BorderLayout.CENTER);
            title.add(buildHttpStatusBadge(progress, dark, base), BorderLayout.EAST);
            textBlock.add(title, BorderLayout.NORTH);
        }
        JPanel metadata = new JPanel(new BorderLayout(5, 0)); metadata.setOpaque(false);
        metadata.add(badge, BorderLayout.WEST);
        if (roomy) {
            JPanel badges = new JPanel(); badges.setOpaque(false); badges.setLayout(new BoxLayout(badges, BoxLayout.X_AXIS));
            if (!step.extractRules().isEmpty()) badges.add(buildRuleBadge("E", "Extract", step.extractRules().size(), dark, base));
            if (!step.injectRules().isEmpty()) { if (badges.getComponentCount() > 0) badges.add(Box.createHorizontalStrut(4)); badges.add(buildRuleBadge("I", "Inject", step.injectRules().size(), dark, base)); }
            if (!step.conditionActionRules().isEmpty()) { if (badges.getComponentCount() > 0) badges.add(Box.createHorizontalStrut(4)); badges.add(buildRuleBadge("C", "Conditions/actions", step.conditionActionRules().size(), dark, base)); }
            metadata.add(badges, BorderLayout.CENTER);
        } else {
            List<String> rules = new ArrayList<>();
            if (!step.extractRules().isEmpty()) rules.add("E" + step.extractRules().size());
            if (!step.injectRules().isEmpty()) rules.add("I" + step.injectRules().size());
            if (!step.conditionActionRules().isEmpty()) rules.add("C" + step.conditionActionRules().size());
            JLabel counts = new JLabel(String.join(" · ", rules));
            counts.setForeground(selected
                    ? (dark ? new Color(0xC2D9F5) : new Color(0x35557A))
                    : (dark ? new Color(0xA6B0BD) : new Color(0x697586)));
            if (base != null) counts.setFont(base.deriveFont(base.getSize2D() - 1f));
            counts.setToolTipText("Extract: " + step.extractRules().size() + " · Inject: " + step.injectRules().size() + " · Conditions/actions: " + step.conditionActionRules().size());
            metadata.add(counts, BorderLayout.CENTER);
        }
        if (progress != null) {
            JLabel state = new JLabel(progress.state().label() + (progress.attempt() > 1 ? " #" + progress.attempt() : ""));
            state.setForeground(switch (progress.state()) {
                case RUNNING -> dark ? new Color(0xF5CE68) : selected ? new Color(0x7A5400) : new Color(0x8A6100);
                case COMPLETED -> dark ? new Color(0x7BE0A6) : new Color(0x13733A);
                case FAILED -> dark ? new Color(0xFFADB6) : new Color(0xB32639);
                default -> selected ? (dark ? new Color(0xC2D9F5) : new Color(0x35557A))
                        : (dark ? new Color(0xA6B0BD) : new Color(0x697586));
            });
            if (base != null) state.setFont(base.deriveFont(base.getSize2D() - 1f));
            state.setToolTipText(progressTooltip(progress));
            state.setHorizontalAlignment(JLabel.RIGHT);
            state.setPreferredSize(new java.awt.Dimension(state.getFontMetrics(state.getFont()).stringWidth("Running #10") + 3, state.getPreferredSize().height));
            metadata.add(state, BorderLayout.EAST);
        }
        textBlock.add(metadata, BorderLayout.SOUTH);

        // ── Remove button ─────────────────────────────────────────────────────
        // Exclude the remove control from the row's selection/drag listener.
        JLabel removeBtn = new JLabel("×");
        Color removeFg = selected ? (dark ? new Color(0xB5CAE3) : new Color(0x4F6E93))
                : (dark ? new Color(0x4A5568) : new Color(0xCBD5E1));
        removeBtn.setForeground(removeFg);
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        removeBtn.setToolTipText("Remove this step");
        if (base != null) removeBtn.setFont(base.deriveFont(base.getSize2D() + 2f));
        removeBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) removeStep(step);
            }
            @Override public void mouseEntered(MouseEvent e) { removeBtn.setForeground(new Color(0xEF4444)); }
            @Override public void mouseExited(MouseEvent e)  {
                removeBtn.setForeground(removeFg);
            }
        });

        // Right side: remove button; metadata stays under the request name.
        JPanel eastPanel = new JPanel();
        eastPanel.setLayout(new BoxLayout(eastPanel, BoxLayout.X_AXIS));
        eastPanel.setOpaque(false);
        eastPanel.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastPanel.add(removeBtn);

        // Align the checkbox with the name; the row and its text already support dragging.
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setOpaque(false);
        leftPanel.add(enableBox, BorderLayout.NORTH);

        row.add(leftPanel,  BorderLayout.WEST);
        row.add(textBlock,  BorderLayout.CENTER);
        row.add(eastPanel,  BorderLayout.EAST);

        // ── Interaction: click to select + drag to reorder + right-click menu ─
        // enableBox and removeBtn are excluded: each handles its own events via its own listener.
        JPopupMenu contextMenu = buildRowContextMenu(step);
        attachRowListeners(row, step, index, contextMenu, Set.of(enableBox, removeBtn));
        if (roomy) {
            int nameHeight = nameLabel.getPreferredSize().height;
            if (base != null) nameHeight = Math.max(row.getFontMetrics(base.deriveFont(Font.BOLD, base.getSize2D() + 1)).getHeight(),
                    row.getFontMetrics(base.deriveFont(Font.PLAIN, base.getSize2D() + 1)).getHeight());
            int fitHeight = Math.max(height, nameHeight + metadata.getPreferredSize().height + 2 + row.getInsets().top + row.getInsets().bottom);
            row.setMinimumSize(new java.awt.Dimension(0, fitHeight));
            row.setPreferredSize(new java.awt.Dimension(320, fitHeight));
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, fitHeight));
        }
        return row;
    }

    private static String progressTooltip(ManualRunProgress.Status status) {
        return status.state().label() + (status.attempt() == 0 ? "" : " · attempt " + status.attempt())
                + (status.statusCode() == 0 ? "" : " · HTTP " + status.statusCode())
                + (status.detail() == null || status.detail().isBlank() ? "" : " · " + status.detail());
    }

    private static JLabel buildHttpStatusBadge(ManualRunProgress.Status status, boolean dark, Font base) {
        boolean finished = status.state() == ManualRunProgress.State.COMPLETED
                || status.state() == ManualRunProgress.State.FAILED || status.state() == ManualRunProgress.State.STOPPED;
        String text = status.statusCode() > 0 ? Integer.toString(status.statusCode()) : finished ? "—" : "";
        JLabel badge = new JLabel(text, JLabel.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                if (!getText().isEmpty()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getBackground());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        badge.setName("requestHttpStatus");
        badge.setOpaque(false);
        int group = status.statusCode() / 100;
        badge.setForeground(group == 2 ? (dark ? new Color(0x7BE0A6) : new Color(0x13733A))
                : group == 3 ? (dark ? new Color(0xF5CE68) : new Color(0x8A6100))
                : group >= 4 ? (dark ? new Color(0xFFADB6) : new Color(0xB32639))
                : dark ? new Color(0xE1E8F0) : new Color(0x34435B));
        badge.setBackground(group == 2 ? (dark ? new Color(0x103321) : new Color(0xEAF8EE))
                : group == 3 ? (dark ? new Color(0x3F340F) : new Color(0xFFF5CC))
                : group >= 4 ? (dark ? new Color(0x3D2028) : new Color(0xFDEDEF))
                : dark ? new Color(0x2B3648) : new Color(0xEDF0F4));
        if (base != null) badge.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 1f));
        badge.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        // Reserve the same width before/after execution; response changes must not resize the sidebar.
        badge.setPreferredSize(new java.awt.Dimension(badge.getFontMetrics(badge.getFont()).stringWidth("999") + 10,
                badge.getFontMetrics(badge.getFont()).getHeight()));
        badge.setToolTipText(status.statusCode() > 0 ? progressTooltip(status)
                : finished ? "No HTTP response · " + progressTooltip(status) : progressTooltip(status));
        return badge;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step mutations
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleStepEnabled(FlowStep step, boolean enabled) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) return;
        List<FlowStep> steps = new ArrayList<>(active.steps());
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(step.id())) {
                FlowStep s = steps.get(i);
                steps.set(i, s.withEnabled(enabled));
                break;
            }
        }
        controller.updateFlow(new FlowDefinition(active.id(), active.name(),
                active.description(), active.enabled(), steps, active.rules(),
                active.aggregation(), active.triggerConfig()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag-and-drop mouse handling
    // ─────────────────────────────────────────────────────────────────────────

    private void attachRowListeners(JPanel row, FlowStep step, int rowIndex,
                                    JPopupMenu contextMenu, Set<Component> excluded) {
        MouseAdapter ma = new MouseAdapter() {
            private boolean dragging = false;

            private void showPopup(MouseEvent event) {
                Point location = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), listContainer);
                dragging = false;
                draggedStepId = null;
                dropTargetIndex = -1;
                dropIndicatorY = -1;
                controller.setSelectedStep(step);
                // Selection can replace the clicked row; anchor the menu to the stable container.
                contextMenu.show(listContainer, location.x, location.y);
            }

            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                dragging = false;
                // Do NOT select or refresh here — that would destroy this row panel
                // mid-operation and break coordinate conversion during drag.
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (!dragging) {
                    dragging = true;
                    draggedStepId = step.id();
                }
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), listContainer);
                updateDropTarget(p.y);
                listContainer.repaint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                    return;
                }
                if (dragging) {
                    if (draggedStepId != null && dropTargetIndex >= 0) {
                        // Capture and clear drag state BEFORE commitDrop so that
                        // the refresh() triggered inside doesn't see draggedStepId != null
                        // and bail out early.
                        String fromId = draggedStepId;
                        int    toIdx  = dropTargetIndex;
                        draggedStepId   = null;
                        dropTargetIndex = -1;
                        dropIndicatorY  = -1;
                        commitDrop(fromId, toIdx);
                        return;
                    }
                } else {
                    // Plain click (no drag) — select the step now.
                    controller.setSelectedStep(step);
                }
                dragging        = false;
                draggedStepId   = null;
                dropTargetIndex = -1;
                dropIndicatorY  = -1;
                listContainer.repaint();
            }
        };

        // Install on the row and all its children. Components in `excluded` handle their own
        // events (enableBox via ActionListener, removeBtn via its own MouseListener).
        installOnTree(row, ma, excluded);
    }

    private static void installOnTree(Component c, MouseAdapter ma, Set<Component> excluded) {
        if (excluded.contains(c)) return;
        c.addMouseListener(ma);
        c.addMouseMotionListener(ma);
        if (c instanceof java.awt.Container ct) {
            for (Component child : ct.getComponents()) installOnTree(child, ma, excluded);
        }
    }

    private void updateDropTarget(int mouseY) {
        Component[] children = listContainer.getComponents();
        for (int i = 0; i < children.length; i++) {
            Component c = children[i];
            if (!(c instanceof JPanel)) continue;  // skip the vertical glue filler
            Rectangle b = c.getBounds();
            if (mouseY <= b.y + b.height / 2) {
                dropTargetIndex = i;
                dropIndicatorY  = b.y;
                return;
            }
        }
        // After last row
        for (int i = children.length - 1; i >= 0; i--) {
            Component c = children[i];
            if (c instanceof JPanel && c.getHeight() > 0) {
                dropTargetIndex = i + 1;
                dropIndicatorY  = c.getBounds().y + c.getHeight();
                return;
            }
        }
    }

    private void commitDrop(String fromStepId, int rawToIndex) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) return;

        List<FlowStep> steps = new ArrayList<>(active.steps());
        int fromIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(fromStepId)) { fromIndex = i; break; }
        }
        if (fromIndex < 0) return;

        int toIndex = rawToIndex;
        if (toIndex > fromIndex) toIndex--;   // compensate for the removal
        toIndex = Math.max(0, Math.min(toIndex, steps.size() - 1));
        if (fromIndex == toIndex) return;

        FlowStep moved = steps.remove(fromIndex);
        steps.add(toIndex, moved);

        // drag state already cleared before this call — refresh() triggered by
        // updateFlow will not be blocked by the draggedStepId guard.
        controller.updateFlow(new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                steps, active.rules(), active.aggregation(), active.triggerConfig()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context menu
    // ─────────────────────────────────────────────────────────────────────────

    private JPopupMenu buildRowContextMenu(FlowStep step) {
        JPopupMenu menu = new JPopupMenu();
        if (accessController != null) {
            JMenuItem convertItem = new JMenuItem("Convert Step to Access Template");
            convertItem.addActionListener(e -> convertToAccessTemplate(step));
            JMenuItem targetItem = new JMenuItem("Add Step as Matrix Target");
            targetItem.addActionListener(e -> addAsMatrixTarget(step));
            JMenuItem setupItem = new JMenuItem("Suggest Setup from This Step");
            setupItem.addActionListener(e -> suggestSetupFromStep(step));
            menu.add(convertItem);
            menu.add(targetItem);
            menu.add(setupItem);
            menu.addSeparator();
        }
        JMenuItem removeItem = new JMenuItem("Remove Step");
        removeItem.addActionListener(e -> removeStep(step));
        menu.add(removeItem);
        return menu;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Badge / pill builders
    // ─────────────────────────────────────────────────────────────────────────

    private static JLabel buildMethodBadge(String method, boolean dark, Font base) {
        Color bg = dark ? methodBgDark(method) : methodBgLight(method);
        Color fg = dark ? methodFgDark(method) : methodFgLight(method);

        JLabel badge = new JLabel(method) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setOpaque(false);
        badge.setBackground(bg);
        badge.setForeground(fg);
        badge.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        badge.setHorizontalAlignment(JLabel.CENTER);
        badge.setAlignmentY(Component.CENTER_ALIGNMENT);
        if (base != null) badge.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 1.5f));
        badge.setMaximumSize(badge.getPreferredSize());
        return badge;
    }

    private static JLabel buildRuleBadge(String shortName, String name, int count, boolean dark, Font base) {
        Color background = switch (shortName) {
            case "E" -> dark ? new Color(0x16382B) : new Color(0xE4F3EB);
            case "I" -> dark ? new Color(0x3C321B) : new Color(0xFBF0D6);
            default -> dark ? new Color(0x292A48) : new Color(0xECEBFA);
        };
        Color foreground = switch (shortName) {
            case "E" -> dark ? new Color(0x8CD3AD) : new Color(0x276344);
            case "I" -> dark ? new Color(0xE6C576) : new Color(0x806218);
            default -> dark ? new Color(0xB9B3EF) : new Color(0x5D5194);
        };
        JLabel label = new JLabel(shortName + " " + count) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D copy = (Graphics2D) g.create();
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(getBackground()); copy.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6); copy.dispose();
                super.paintComponent(g);
            }
        };
        label.setName("flowiseRuleBadge"); label.setOpaque(false); label.setBackground(background); label.setForeground(foreground);
        label.setToolTipText(name + ": " + count); label.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        if (base != null) label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 1.5f));
        label.setMaximumSize(label.getPreferredSize()); return label;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step actions
    // ─────────────────────────────────────────────────────────────────────────

    private void removeStep(FlowStep step) {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Remove step \"" + step.name() + "\"?",
                "Confirm Remove",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        FlowDefinition active = controller.activeFlow();
        if (active == null) return;
        List<FlowStep> updated = new ArrayList<>(active.steps());
        updated.removeIf(s -> s.id().equals(step.id()));
        AggregationPlan agg = active.aggregation();
        if (agg.enabled() && agg.finalStepId().equals(step.id()))
            agg = AggregationPlan.disabled();
        if (step.id().equals(selectedStepId)) {
            controller.setSelectedStep(null);
        }
        controller.updateFlow(new FlowDefinition(active.id(), active.name(),
                active.description(), active.enabled(), updated, active.rules(),
                agg, active.triggerConfig()));
    }

    private void convertToAccessTemplate(FlowStep step) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                confirmationText("Create a disabled Access Template from this step?", active, step),
                "Convert to Access Template",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            AccessRequestTemplate template =
                    accessController.createTemplateFromFlowStep(active, step, OperationMetadata.unknown());
            JOptionPane.showMessageDialog(
                    this,
                    "Created disabled Access Template:\n" + template.name(),
                    "Access Template Created",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Access Template", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addAsMatrixTarget(FlowStep step) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) {
            return;
        }
        if (accessController.authProfiles().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Create at least one auth profile before adding a matrix target.",
                    "Add Matrix Target",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        MatrixTargetPanel form = new MatrixTargetPanel(accessController);
        int choice = JOptionPane.showConfirmDialog(
                this,
                withSummary(form, "Create a disabled matrix row from this step?", active, step),
                "Add as Matrix Target",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            AccessTestCase row = accessController.addFlowStepAsMatrixTarget(
                    active,
                    step,
                    form.authProfileId(),
                    form.dataProfileId(),
                    form.expected());
            JOptionPane.showMessageDialog(
                    this,
                    "Created disabled matrix row:\n" + row.name(),
                    "Matrix Target Created",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Add Matrix Target", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void suggestSetupFromStep(FlowStep step) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) {
            return;
        }
        SetupSuggestionResult suggestion =
                accessController.suggestSetupFromFlow(active.id(), step.id(), RenderMode.STRICT);
        JTextArea text = new JTextArea(setupSuggestionText(suggestion));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setRows(12);
        text.setColumns(52);
        JOptionPane.showMessageDialog(
                this,
                new JScrollPane(text),
                "Setup Suggestion",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static JPanel withSummary(JPanel form, String title, FlowDefinition flow, FlowStep step) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JTextArea summary = new JTextArea(confirmationText(title, flow, step));
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setOpaque(false);
        panel.add(summary, BorderLayout.NORTH);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private static String confirmationText(String title, FlowDefinition flow, FlowStep step) {
        StoredRequestTemplate request = step.requestTemplate();
        return title + "\n\n"
                + "Flow: " + flow.name() + "\n"
                + "Step: " + step.name() + "\n"
                + "Request: " + request.method() + " " + request.host() + request.pathAndQuery() + "\n\n"
                + "No HTTP request will be sent. Generated artifacts are disabled by default.";
    }

    private static String setupSuggestionText(SetupSuggestionResult suggestion) {
        StringBuilder text = new StringBuilder();
        text.append("Result: ").append(suggestion.success() ? "RUN_PREFIX can be suggested" : "Manual setup needed")
                .append('\n');
        if (!suggestion.reason().isBlank()) {
            text.append("Reason: ").append(suggestion.reason()).append('\n');
        }
        text.append('\n').append("Suggested setup: ")
                .append(suggestion.suggestedSetup().mode()).append('\n')
                .append("Setup flow: ").append(suggestion.suggestedSetup().setupFlowId()).append('\n')
                .append("Until before step: ").append(suggestion.suggestedSetup().untilBeforeStepId()).append('\n')
                .append("Scenario policy: ").append(suggestion.suggestedSetup().scenarioPolicy()).append('\n');
        text.append('\n').append("Required scenario variables").append('\n');
        if (suggestion.requiredScenarioVariables().isEmpty()) {
            text.append("- none").append('\n');
        } else {
            suggestion.requiredScenarioVariables().forEach(ref ->
                    text.append("- ").append(ref.scopedKey())
                            .append(" from ")
                            .append(suggestion.producerStepIds().getOrDefault(ref, "missing producer"))
                            .append('\n'));
        }
        if (!suggestion.warnings().isEmpty()) {
            text.append('\n').append("Warnings").append('\n');
            suggestion.warnings().forEach(warning -> text.append("- ").append(warning).append('\n'));
        }
        return text.toString();
    }

    private static final class MatrixTargetPanel extends JPanel {
        private final JComboBox<ComboItem> auth = new JComboBox<>();
        private final JComboBox<ComboItem> data = new JComboBox<>();
        private final JComboBox<ExpectedResult> expected = new JComboBox<>(ExpectedResult.values());

        private MatrixTargetPanel(AccessUiController controller) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            for (AuthProfile profile : controller.authProfiles()) {
                auth.addItem(new ComboItem(profile.id(), profile.name()));
            }
            data.addItem(new ComboItem("", ""));
            for (DataProfile profile : controller.dataProfiles()) {
                data.addItem(new ComboItem(profile.id(), profile.name()));
            }
            expected.setSelectedItem(ExpectedResult.REVIEW);
            add(labelled("Target Auth", auth));
            add(labelled("Data Profile", data));
            add(labelled("Expected", expected));
        }

        private String authProfileId() {
            ComboItem item = (ComboItem) auth.getSelectedItem();
            return item == null ? "" : item.id();
        }

        private String dataProfileId() {
            ComboItem item = (ComboItem) data.getSelectedItem();
            return item == null ? "" : item.id();
        }

        private ExpectedResult expected() {
            return (ExpectedResult) expected.getSelectedItem();
        }

        private static JPanel labelled(String label, java.awt.Component component) {
            JPanel panel = new JPanel(new BorderLayout(6, 2));
            panel.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            panel.add(new JLabel(label), BorderLayout.NORTH);
            panel.add(component, BorderLayout.CENTER);
            return panel;
        }
    }

    private record ComboItem(String id, String label) {
        private ComboItem {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
        }

        @Override
        public String toString() {
            return label.isBlank() ? id : label;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modern muted method colour tables
    // ─────────────────────────────────────────────────────────────────────────

    private static Color methodBgLight(String m) { return switch (m) {
        case "GET"     -> new Color(0xD1FAE5);
        case "POST"    -> new Color(0xFEF3C7);
        case "PUT"     -> new Color(0xDBEAFE);
        case "PATCH"   -> new Color(0xEDE9FE);
        case "DELETE"  -> new Color(0xFEE2E2);
        case "HEAD"    -> new Color(0xE0F2FE);
        case "OPTIONS" -> new Color(0xD1FAE5);
        default        -> new Color(0xF1F5F9);
    };}
    private static Color methodFgLight(String m) { return switch (m) {
        case "GET"     -> new Color(0x065F46);
        case "POST"    -> new Color(0x92400E);
        case "PUT"     -> new Color(0x1E40AF);
        case "PATCH"   -> new Color(0x5B21B6);
        case "DELETE"  -> new Color(0x991B1B);
        case "HEAD"    -> new Color(0x075985);
        case "OPTIONS" -> new Color(0x065F46);
        default        -> new Color(0x475569);
    };}
    private static Color methodBgDark(String m) { return switch (m) {
        case "GET"     -> new Color(0x064E3B);
        case "POST"    -> new Color(0x78350F);
        case "PUT"     -> new Color(0x1E3A5F);
        case "PATCH"   -> new Color(0x4C1D95);
        case "DELETE"  -> new Color(0x7F1D1D);
        case "HEAD"    -> new Color(0x0C4A6E);
        case "OPTIONS" -> new Color(0x064E3B);
        default        -> new Color(0x1E293B);
    };}
    private static Color methodFgDark(String m) { return switch (m) {
        case "GET"     -> new Color(0x6EE7B7);
        case "POST"    -> new Color(0xFBBF24);
        case "PUT"     -> new Color(0x93C5FD);
        case "PATCH"   -> new Color(0xDDD6FE);
        case "DELETE"  -> new Color(0xFCA5A5);
        case "HEAD"    -> new Color(0x7DD3FC);
        case "OPTIONS" -> new Color(0xA7F3D0);
        default        -> new Color(0x94A3B8);
    };}
}
