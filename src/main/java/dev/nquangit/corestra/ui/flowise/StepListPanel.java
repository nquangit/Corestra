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
    private final JPanel listContainer;

    private List<FlowStep> renderedSteps;
    private boolean renderedDark;
    private Color renderedBackground;
    // Last rendered selection; only refresh() updates it from the controller.
    private String  selectedStepId  = null;
    private String  draggedStepId   = null;
    private int     dropTargetIndex = -1;
    private int     dropIndicatorY  = -1;

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
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.accessController = accessController;

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
            if (Objects.equals(previousSelection, selectedStepId)) return;
            for (int i = 0; i < steps.size(); i++) {
                String id = steps.get(i).id();
                if (id.equals(previousSelection) || id.equals(selectedStepId)) {
                    listContainer.remove(i);
                    listContainer.add(buildRow(steps.get(i), i, dark, listBg), i);
                }
            }
            listContainer.revalidate();
            listContainer.repaint();
            return;
        }
        renderedSteps = steps;
        renderedDark = dark;
        renderedBackground = listBg;
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

        // ── Colours ──────────────────────────────────────────────────────────
        Color sep = dark ? new Color(0x1E293B) : new Color(0xE9EEF5);

        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (selected) {
                    // Left accent bar for selected row
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(0x3B82F6));
                    g2.fillRect(0, 0, 3, getHeight());
                    g2.dispose();
                }
            }
        };

        if (selected) {
            row.setBackground(dark ? new Color(0x1A2840) : new Color(0xEFF6FF));
        } else {
            row.setBackground(listBg);
        }
        row.setOpaque(true);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, sep),
                BorderFactory.createEmptyBorder(7, 12, 7, 10)));
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 68));

        // ── Drag handle ───────────────────────────────────────────────────────
        JLabel handle = new JLabel("⠿");
        handle.setForeground(dark ? new Color(0x3D5068) : new Color(0xCBD5E1));
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        handle.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        Font base = UIManager.getFont("Label.font");
        if (base != null) handle.setFont(base.deriveFont(base.getSize2D() + 1f));

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
        String path   = tmpl != null && tmpl.pathAndQuery() != null ? tmpl.pathAndQuery() : "/";
        String host   = tmpl != null && tmpl.host() != null ? tmpl.host() : "";
        if (path.isEmpty()) path = "/";
        if (path.length() > 42) path = path.substring(0, 42) + "…";

        JLabel badge = buildMethodBadge(method, dark, base);

        // ── Text block ────────────────────────────────────────────────────────
        Color nameFg = selected
                ? (dark ? new Color(0xBAC8FF) : new Color(0x1D4ED8))
                : (step.enabled()
                        ? (dark ? new Color(0xCDD5E0) : new Color(0x1E293B))
                        : (dark ? new Color(0x4A5568) : new Color(0xA0AEC0)));
        Color pathFg = selected
                ? (dark ? new Color(0x7FB3FF) : new Color(0x3B82F6))
                : (dark ? new Color(0x64748B) : new Color(0x64748B));
        Color hostFg = dark ? new Color(0x374151) : new Color(0xADB5BD);

        JLabel nameLabel = new JLabel(step.name());
        nameLabel.setForeground(nameFg);
        if (base != null) nameLabel.setFont(base.deriveFont(Font.BOLD, base.getSize2D()));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel pathLabel = new JLabel(path);
        pathLabel.setForeground(pathFg);
        if (base != null) pathLabel.setFont(base.deriveFont(base.getSize2D() - 1.5f));
        pathLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hostLabel = new JLabel(host);
        hostLabel.setForeground(hostFg);
        if (base != null) hostLabel.setFont(base.deriveFont(base.getSize2D() - 1.5f));
        hostLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel textBlock = new JPanel();
        textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
        textBlock.setOpaque(false);
        textBlock.add(nameLabel);
        textBlock.add(pathLabel);
        textBlock.add(hostLabel);

        // ── Rule badges ───────────────────────────────────────────────────────
        JPanel badges = new JPanel();
        badges.setLayout(new BoxLayout(badges, BoxLayout.X_AXIS));
        badges.setOpaque(false);

        if (!step.extractRules().isEmpty()) {
            badges.add(buildPill("E " + step.extractRules().size(),
                    dark ? new Color(0x052E16) : new Color(0xD1FAE5),
                    dark ? new Color(0x34D399) : new Color(0x065F46), base));
        }
        if (!step.injectRules().isEmpty()) {
            if (!step.extractRules().isEmpty()) badges.add(Box.createHorizontalStrut(4));
            badges.add(buildPill("I " + step.injectRules().size(),
                    dark ? new Color(0x3B1F08) : new Color(0xFEF3C7),
                    dark ? new Color(0xFBBF24) : new Color(0x92400E), base));
        }
        if (!step.conditionActionRules().isEmpty()) {
            if (!step.extractRules().isEmpty() || !step.injectRules().isEmpty()) badges.add(Box.createHorizontalStrut(4));
            badges.add(buildPill("C " + step.conditionActionRules().size(),
                    dark ? new Color(0x111827) : new Color(0xE0E7FF),
                    dark ? new Color(0xA5B4FC) : new Color(0x3730A3), base));
        }

        // ── Remove button ─────────────────────────────────────────────────────
        // Plain JButton instead of StyledButton — keeps the row compact and avoids
        // the drag listener swallowing its events (it is passed as excluded below).
        JLabel removeBtn = new JLabel("×");
        removeBtn.setForeground(dark ? new Color(0x4A5568) : new Color(0xCBD5E1));
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
                removeBtn.setForeground(dark ? new Color(0x4A5568) : new Color(0xCBD5E1));
            }
        });

        // Right side: rule pills + remove button
        JPanel eastPanel = new JPanel();
        eastPanel.setLayout(new BoxLayout(eastPanel, BoxLayout.X_AXIS));
        eastPanel.setOpaque(false);
        eastPanel.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastPanel.add(badges);
        eastPanel.add(removeBtn);

        // Left side: handle + enable checkbox + method badge
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.X_AXIS));
        leftPanel.setOpaque(false);
        leftPanel.setAlignmentY(Component.CENTER_ALIGNMENT);
        leftPanel.add(handle);
        leftPanel.add(enableBox);
        leftPanel.add(Box.createHorizontalStrut(4));
        leftPanel.add(badge);

        row.add(leftPanel,  BorderLayout.WEST);
        row.add(textBlock,  BorderLayout.CENTER);
        row.add(eastPanel,  BorderLayout.EAST);

        // ── Interaction: click to select + drag to reorder + right-click menu ─
        // enableBox and removeBtn are excluded: each handles its own events via its own listener.
        JPopupMenu contextMenu = buildRowContextMenu(step);
        attachRowListeners(row, step, index, contextMenu, Set.of(enableBox, removeBtn));
        return row;
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
        badge.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7));
        badge.setHorizontalAlignment(JLabel.CENTER);
        badge.setAlignmentY(Component.CENTER_ALIGNMENT);
        if (base != null) badge.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 0.5f));
        badge.setPreferredSize(new java.awt.Dimension(58, badge.getPreferredSize().height + 4));
        return badge;
    }

    private static JLabel buildPill(String text, Color bg, Color fg, Font base) {
        JLabel pill = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        pill.setOpaque(false);
        pill.setBackground(bg);
        pill.setForeground(fg);
        pill.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        pill.setAlignmentY(Component.CENTER_ALIGNMENT);
        if (base != null) pill.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 1.5f));
        return pill;
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
