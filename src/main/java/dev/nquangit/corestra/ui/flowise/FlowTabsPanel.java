package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.ui.component.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@link JTabbedPane} whose flow tabs are rendered as distinct coloured boxes.
 *
 * <h3>Layout</h3>
 * {@code JTabbedPane.tabInsets} is set to a small value so each custom tab
 * component is positioned with a visible gap from its neighbours — the
 * "divided boxes" effect.  FlatLaf still handles all layout arithmetic; we
 * only supply the visual content via {@code setTabComponentAt}.
 *
 * <h3>Painting</h3>
 * Each tab panel's {@code paintComponent} draws a rounded-rect fill (subtle
 * accent tint) plus a 1–1.5 px accent border.  It never touches child
 * component state, so there are no repaints or layout cycles beyond a normal
 * repaint.
 *
 * <h3>+ button</h3>
 * Uses {@code JTabbedPane.trailingComponent} — no pseudo-tab needed.
 */
public final class FlowTabsPanel extends JTabbedPane {

    private static final Color[] ACCENT_LIGHT = {
            new Color(0x6366F1), new Color(0x0EA5E9), new Color(0x10B981),
            new Color(0xF59E0B), new Color(0xF43F5E), new Color(0x8B5CF6),
            new Color(0xF97316), new Color(0x14B8A6),
    };
    private static final Color[] ACCENT_DARK = {
            new Color(0x818CF8), new Color(0x38BDF8), new Color(0x34D399),
            new Color(0xFBBF24), new Color(0xFB7185), new Color(0xA78BFA),
            new Color(0xFB923C), new Color(0x2DD4BF),
    };
    private static final String[] COLOR_NAMES = {
            "Indigo", "Sky", "Emerald", "Amber", "Rose", "Violet", "Orange", "Teal",
    };
    private static final String PREF_PREFIX = "corestra.flow.color.";

    private final FlowWorkspaceController controller;
    private final MontoyaApi api;
    private final Map<String, FlowWorkspacePanel> panelCache = new LinkedHashMap<>();
    private boolean refreshing = false;

    public FlowTabsPanel(FlowWorkspaceController controller, MontoyaApi api) {
        super(JTabbedPane.TOP);
        this.controller = Objects.requireNonNull(controller, "controller");
        this.api        = Objects.requireNonNull(api, "api");

        // Small insets → visible gap between adjacent coloured boxes.
        putClientProperty("JTabbedPane.tabInsets", new Insets(3, 4, 3, 4));

        // Suppress FlatLaf's selection underline for this pane only.
        putClientProperty("JTabbedPane.tabType", "underline");
        putClientProperty("JTabbedPane.underlineColor",         new Color(0, 0, 0, 0));
        putClientProperty("JTabbedPane.selectedUnderlineColor", new Color(0, 0, 0, 0));
        putClientProperty("JTabbedPane.underlineHeight", 0);

        addChangeListener(onTabSelected());
        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab selection
    // ─────────────────────────────────────────────────────────────────────────

    private ChangeListener onTabSelected() {
        return e -> {
            if (refreshing) return;
            int idx = getSelectedIndex();
            if (idx < 0) return;
            List<FlowDefinition> flows = controller.flows();
            if (idx == flows.size()) {
                // "+" pseudo-tab — revert selection then open the add dialog
                SwingUtilities.invokeLater(() -> {
                    FlowDefinition active = controller.activeFlow();
                    int prev = active != null ? indexOfFlow(flows, active.id()) : -1;
                    if (prev >= 0) setSelectedIndex(prev);
                    else if (!flows.isEmpty()) setSelectedIndex(0);
                    addFlow();
                });
                return;
            }
            if (idx >= flows.size()) return;
            FlowDefinition selected = flows.get(idx);
            if (!Objects.equals(selected, controller.activeFlow())) {
                controller.setActiveFlow(selected);
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh
    // ─────────────────────────────────────────────────────────────────────────

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            List<FlowDefinition> flows = controller.flows();
            for (FlowDefinition flow : flows) {
                panelCache.computeIfAbsent(flow.id(), id -> new FlowWorkspacePanel(controller, api));
            }
            panelCache.keySet().retainAll(flows.stream().map(FlowDefinition::id).toList());

            removeAll();
            for (int i = 0; i < flows.size(); i++) {
                FlowDefinition flow = flows.get(i);
                addTab(flow.name(), panelCache.get(flow.id()));
                setTabComponentAt(i, buildFlowTabComponent(flow));
            }

            FlowDefinition active = controller.activeFlow();
            if (active == null && !flows.isEmpty()) {
                // Auto-select first flow on initial load (activeFlow is not persisted).
                // refreshing=true blocks our own re-entrant refresh(); other listeners (StepListPanel) run normally.
                active = flows.get(0);
                controller.setActiveFlow(active);
            }
            if (active != null) {
                int idx = indexOfFlow(flows, active.id());
                if (idx >= 0) setSelectedIndex(idx);
            }

            // "+" pseudo-tab — must be added after the active-tab selection so indices stay correct.
            // Using a MouseListener on the label directly because JLabel-as-tab-component intercepts
            // mouse events before they reach the JTabbedPane's own selection mechanism.
            addTab("", new JPanel());
            JLabel addLabel = new JLabel(" + ");
            Font af = addLabel.getFont();
            if (af != null) addLabel.setFont(af.deriveFont(Font.BOLD));
            addLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addLabel.setToolTipText("Add new flow");
            addLabel.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) addFlow();
                }
            });
            setTabComponentAt(getTabCount() - 1, addLabel);

            revalidate();
            repaint();
        } finally {
            refreshing = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab component builder
    // ─────────────────────────────────────────────────────────────────────────

    private Component buildFlowTabComponent(FlowDefinition flow) {
        final Color accent = resolveAccent(flow);

        JLabel name = new JLabel(flow.name());
        Font base = UIManager.getFont("Label.font");
        if (base != null) name.setFont(base);

        JButton close = new JButton("×");
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 4, 0, 0));
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Font cf = close.getFont();
        if (cf != null) close.setFont(cf.deriveFont(cf.getSize2D() + 1f));
        close.setToolTipText("Delete this flow");
        close.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { close.setForeground(new Color(0xEF4444)); }
            @Override public void mouseExited(MouseEvent e)  { close.setForeground(UIManager.getColor("Label.foreground")); }
        });
        close.addActionListener(e -> deleteFlow(flow));

        // paintComponent paints background + border only — never touches child state.
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                boolean sel = isSelectedTabComponent(this);
                int r = accent.getRed(), gr = accent.getGreen(), b = accent.getBlue();

                // Background tint: stronger on active tab.
                g2.setColor(new Color(r, gr, b, sel ? 40 : 18));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                // Border: solid on active, lighter on inactive.
                g2.setColor(new Color(r, gr, b, sel ? 210 : 110));
                g2.setStroke(new BasicStroke(sel ? 1.5f : 1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));
        panel.add(name);
        panel.add(close);

        JPopupMenu menu = buildTabContextMenu(flow);
        MouseAdapter handler = buildTabHandler(panel, menu);
        installHandler(panel, handler, close);

        return panel;
    }

    private MouseAdapter buildTabHandler(JPanel panel, JPopupMenu menu) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || refreshing) return;
                for (int i = 0; i < getTabCount(); i++) {
                    if (getTabComponentAt(i) == panel) { setSelectedIndex(i); break; }
                }
            }
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY()); }
        };
    }

    private boolean isSelectedTabComponent(Component comp) {
        int sel = getSelectedIndex();
        return sel >= 0 && sel < getTabCount() && getTabComponentAt(sel) == comp;
    }

    private static void installHandler(Component root, MouseAdapter h, Component excluded) {
        if (root == excluded) return;
        root.addMouseListener(h);
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) installHandler(child, h, excluded);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context menu
    // ─────────────────────────────────────────────────────────────────────────

    private JPopupMenu buildTabContextMenu(FlowDefinition flow) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem rename = new JMenuItem("Rename…");
        rename.addActionListener(e -> renameFlow(flow));
        menu.add(rename);

        JMenuItem duplicate = new JMenuItem("Duplicate");
        duplicate.addActionListener(e -> duplicateFlow(flow));
        menu.add(duplicate);

        menu.addSeparator();

        JMenu colorMenu = new JMenu("Change Color");
        ButtonGroup group = new ButtonGroup();
        int current = resolveColorIndex(flow);
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(COLOR_NAMES[i]);
            item.setIcon(colorSwatch(ACCENT_LIGHT[i]));
            item.setSelected(i == current);
            final int colorIdx = i;
            item.addActionListener(e -> { saveColorIndex(flow.id(), colorIdx); refresh(); });
            group.add(item);
            colorMenu.add(item);
        }
        menu.add(colorMenu);

        return menu;
    }

    private static Icon colorSwatch(Color color) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillRoundRect(x + 1, y + 1, 13, 13, 4, 4);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Colour persistence
    // ─────────────────────────────────────────────────────────────────────────

    private int loadColorIndex(String flowId) {
        try {
            String val = api.persistence().preferences().getString(PREF_PREFIX + flowId);
            if (val == null) return -1;
            int idx = Integer.parseInt(val.trim());
            return (idx >= 0 && idx < ACCENT_LIGHT.length) ? idx : -1;
        } catch (Exception ignored) { return -1; }
    }

    private void saveColorIndex(String flowId, int index) {
        api.persistence().preferences().setString(PREF_PREFIX + flowId, String.valueOf(index));
    }

    private int resolveColorIndex(FlowDefinition flow) {
        int stored = loadColorIndex(flow.id());
        return stored >= 0 ? stored : Math.abs(flow.id().hashCode()) % ACCENT_LIGHT.length;
    }

    private Color resolveAccent(FlowDefinition flow) {
        int idx = resolveColorIndex(flow);
        return (UiTheme.isDark() ? ACCENT_DARK : ACCENT_LIGHT)[idx];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow actions
    // ─────────────────────────────────────────────────────────────────────────

    void addFlow() {
        String name = JOptionPane.showInputDialog(this, "Flow name:", "New Flow", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        FlowDefinition flow = new FlowDefinition(
                UUID.randomUUID().toString(), name.strip(), "", true,
                List.of(), FlowRuleSet.empty(), AggregationPlan.disabled(), FlowTriggerConfig.none());
        saveColorIndex(flow.id(), controller.flows().size() % ACCENT_LIGHT.length);
        controller.addFlow(flow);
        controller.setActiveFlow(flow);
    }

    private void renameFlow(FlowDefinition flow) {
        String name = (String) JOptionPane.showInputDialog(
                this, "New name:", "Rename Flow",
                JOptionPane.PLAIN_MESSAGE, null, null, flow.name());
        if (name == null || name.isBlank()) return;
        FlowDefinition updated = new FlowDefinition(
                flow.id(), name.strip(), flow.description(), flow.enabled(),
                flow.steps(), flow.rules(), flow.aggregation(), flow.triggerConfig());
        controller.updateFlow(updated);
    }

    private void duplicateFlow(FlowDefinition flow) {
        FlowDefinition copy = new FlowDefinition(
                UUID.randomUUID().toString(),
                flow.name() + " (copy)", flow.description(), flow.enabled(),
                flow.steps(), flow.rules(), AggregationPlan.disabled(), FlowTriggerConfig.none());
        saveColorIndex(copy.id(), controller.flows().size() % ACCENT_LIGHT.length);
        controller.addFlow(copy);
        controller.setActiveFlow(copy);
    }

    private void deleteFlow(FlowDefinition flow) {
        int confirm = JOptionPane.showConfirmDialog(
                this, "Delete flow \"" + flow.name() + "\"?", "Delete Flow",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) controller.removeFlow(flow);
    }

    private static int indexOfFlow(List<FlowDefinition> flows, String flowId) {
        for (int i = 0; i < flows.size(); i++) {
            if (flows.get(i).id().equals(flowId)) return i;
        }
        return -1;
    }
}
