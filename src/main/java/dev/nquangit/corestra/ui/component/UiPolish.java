package dev.nquangit.corestra.ui.component;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.table.JTableHeader;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

/**
 * Applies small, theme-safe visual and interaction improvements across Corestra workspaces.
 */
public final class UiPolish {
    private static final int TABLE_ROW_HEIGHT = 26;
    private static final int TAB_HEIGHT = 30;
    private static final int SPLIT_DIVIDER_SIZE = 7;

    private UiPolish() {
    }

    /**
     * Applies polish to a component tree after Burp has applied its active theme.
     *
     * @param root root component
     */
    public static void apply(Component root) {
        if (root == null) {
            return;
        }
        polish(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                apply(child);
            }
        }
    }

    private static void polish(Component component) {
        if (component instanceof JTable table) {
            table.setRowHeight(Math.max(table.getRowHeight(), TABLE_ROW_HEIGHT));
            table.setIntercellSpacing(new Dimension(0, 1));
            table.setShowVerticalLines(false);
            table.setShowHorizontalLines(true);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            JTableHeader header = table.getTableHeader();
            if (header != null) {
                header.setReorderingAllowed(false);
                Font font = header.getFont();
                if (font != null) {
                    header.setFont(font.deriveFont(Font.BOLD));
                }
            }
        } else if (component instanceof JTabbedPane tabs) {
            tabs.putClientProperty("JTabbedPane.tabHeight", TAB_HEIGHT);
            tabs.putClientProperty("JTabbedPane.showTabSeparators", Boolean.TRUE);
            if (tabs.getTabCount() > 4) {
                tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            }
        } else if (component instanceof JSplitPane splitPane) {
            splitPane.setContinuousLayout(true);
            splitPane.setDividerSize(Math.max(splitPane.getDividerSize(), SPLIT_DIVIDER_SIZE));
            splitPane.setBorder(BorderFactory.createEmptyBorder());
        } else if (component instanceof JScrollPane scrollPane) {
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        } else if (component instanceof JTextField textField) {
            textField.setMargin(new Insets(4, 6, 4, 6));
        } else if (component instanceof JTextArea textArea) {
            textArea.setMargin(new Insets(6, 7, 6, 7));
        } else if (component instanceof JComboBox<?> comboBox) {
            comboBox.setMaximumRowCount(Math.max(comboBox.getMaximumRowCount(), 16));
        } else if (component instanceof JList<?> list) {
            list.setFixedCellHeight(Math.max(list.getFixedCellHeight(), 28));
        }

        if (component instanceof JComponent swingComponent) {
            swingComponent.putClientProperty("JComponent.minimumWidth", 0);
        }
    }
}
