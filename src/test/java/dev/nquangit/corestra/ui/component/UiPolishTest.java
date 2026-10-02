package dev.nquangit.corestra.ui.component;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import java.awt.BorderLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPolishTest {
    @Test
    void appliesReadableDefaultsAcrossNestedWorkspaceComponents() {
        JTable table = new JTable(new Object[][]{{"value"}}, new Object[]{"Column"});
        JScrollPane scrollPane = new JScrollPane(table);
        JTextField field = new JTextField();
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, field);
        splitPane.setDividerSize(4);
        JTabbedPane tabs = new JTabbedPane();
        for (int index = 0; index < 5; index++) {
            tabs.addTab("Tab " + index, new JLabel("Content"));
        }
        JPanel root = new JPanel(new BorderLayout());
        root.add(splitPane, BorderLayout.CENTER);
        root.add(tabs, BorderLayout.SOUTH);

        UiPolish.apply(root);

        assertTrue(table.getRowHeight() >= 26);
        assertFalse(table.getShowVerticalLines());
        assertTrue(table.getShowHorizontalLines());
        assertFalse(table.getTableHeader().getReorderingAllowed());
        assertEquals(Boolean.TRUE, table.getClientProperty("terminateEditOnFocusLost"));
        assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, tabs.getTabLayoutPolicy());
        assertTrue(splitPane.isContinuousLayout());
        assertTrue(splitPane.getDividerSize() >= 7);
        assertTrue(field.getMargin().left >= 6);
        assertTrue(scrollPane.getVerticalScrollBar().getUnitIncrement() >= 16);
    }

    @Test
    void styledButtonsKeepAComfortableClickTarget() {
        assertTrue(StyledButton.primary("Add").getPreferredSize().height >= 28);
        assertTrue(StyledButton.neutral("Edit").getPreferredSize().height >= 28);
        assertTrue(StyledButton.danger("Delete").getPreferredSize().height >= 28);
        assertTrue(StyledButton.success("Run").getPreferredSize().height >= 28);
    }
}
