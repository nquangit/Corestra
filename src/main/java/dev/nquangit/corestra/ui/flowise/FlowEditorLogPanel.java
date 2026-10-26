package dev.nquangit.corestra.ui.flowise;

import javax.swing.*;
import java.awt.*;

/** Switches the entire workspace between editing and history without duplicating native editors. */
public final class FlowEditorLogPanel extends JPanel {
    private final CardLayout layout = new CardLayout();
    private final JPanel cards = new JPanel(layout);
    private final JToggleButton editor = new JToggleButton("Request editor"), history = new JToggleButton("Flow Log");

    /** Reuses the supplied editor and log at full available height. */
    public FlowEditorLogPanel(JComponent editorPanel, FlowLogPanel logPanel) {
        super(new BorderLayout(0, 3));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        ButtonGroup group = new ButtonGroup(); group.add(editor); group.add(history);
        controls.add(editor); controls.add(history);
        history.setToolTipText("Use the full workspace to browse actual sent requests and received responses");
        cards.add(editorPanel, "editor"); cards.add(logPanel, "log");
        editor.addActionListener(e -> showLog(false)); history.addActionListener(e -> showLog(true));
        add(controls, BorderLayout.NORTH); add(cards, BorderLayout.CENTER); showLog(false);
    }

    /** Returns whether the full-height history view is active. */
    public boolean isLogVisible() { return history.isSelected(); }

    /** Changes views without modifying the selected request or any unsaved editor content. */
    public void showLog(boolean visible) {
        (visible ? history : editor).setSelected(true); layout.show(cards, visible ? "log" : "editor");
    }
}
