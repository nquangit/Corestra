package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.persistence.*;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.component.WrappedText;
import dev.nquangit.corestra.ui.component.TableReviewPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
@ResourceLock("CorestraPromptDialogs")
class TriggerPanelTest {
    @Test void reviewDialogsUsePlainWrappedGuidanceFitTheScreenAndKeepCancelAndApplyWorking() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        var controllerRef = new java.util.concurrent.atomic.AtomicReference<FlowWorkspaceController>();
        var panelRef = new java.util.concurrent.atomic.AtomicReference<TriggerPanel>();
        SwingUtilities.invokeAndWait(() -> {
            FlowisePerformanceTest.store = new java.util.concurrent.ConcurrentHashMap<>();
            var controller = new FlowWorkspaceController(new FlowRepository(FlowisePerformanceTest.persistence(), new FlowSerializer(), new PersistenceMigrationService()));
            var original = FlowisePerformanceTest.flow("review");
            var rule = new InjectRule("inject", "Actor", true, InjectRule.Target.REQUEST_HEADER, "X-Actor",
                    InjectRule.ValueSourceType.VARIABLE, "credentials.username", "", null);
            var step = original.steps().getFirst().withInjectRules(List.of(rule));
            var flow = new FlowDefinition(original.id(), original.name(), "", true, List.of(step), null, null, null);
            controller.addFlow(flow); controller.setActiveFlow(flow); controller.setSelectedStep(step);
            controllerRef.set(controller); panelRef.set(new TriggerPanel(controller));
        });
        try {
            SwingUtilities.invokeLater(() -> ((JButton) find(panelRef.get(), "triggerCheckInputs")).doClick());
            JDialog check = awaitDialog("Auto-trigger input check");
            SwingUtilities.invokeAndWait(() -> {
                assertPlainAndBounded(check);
                findType(check, JOptionPane.class).setValue(JOptionPane.OK_OPTION);
            });
            for (int choice : List.of(JOptionPane.CANCEL_OPTION, JOptionPane.OK_OPTION, JOptionPane.OK_OPTION)) {
                var closed = new java.util.concurrent.CountDownLatch(1);
                SwingUtilities.invokeLater(() -> {
                    try { findButton(panelRef.get(), "Target inject rules…").doClick(); }
                    finally { closed.countDown(); }
                });
                JDialog target = awaitDialog("Auto-trigger target inject rules");
                var expected = new java.util.concurrent.atomic.AtomicReference<List<String>>();
                SwingUtilities.invokeAndWait(() -> {
                    assertPlainAndBounded(target);
                    var table = findType(target, JTable.class);
                    table.setValueAt(!Boolean.TRUE.equals(table.getValueAt(0, 0)), 0, 0);
                    expected.set(choice == JOptionPane.CANCEL_OPTION ? List.of()
                            : Boolean.FALSE.equals(table.getValueAt(0, 0)) ? List.of("inject") : List.of());
                    findType(target, JOptionPane.class).setValue(choice);
                });
                assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    var exclusions = controllerRef.get().selectedStep().triggerRule().inputBindings().excludedTargetRuleIds();
                    assertEquals(expected.get(), exclusions);
                    assertTrue(controllerRef.get().selectedStep().injectRules().getFirst().enabled());
                });
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (var window : java.awt.Window.getWindows()) if (window instanceof JDialog dialog
                        && dialog.getTitle().startsWith("Auto-trigger")) dialog.dispose();
            });
        }
    }

    private static void assertPlainAndBounded(JDialog dialog) {
        var guidance = findType(dialog, WrappedText.class);
        assertNotNull(guidance); assertTrue(guidance.getText().contains("\n"));
        assertFalse(guidance.getText().contains("<br>")); assertFalse(guidance.getText().contains("<html>"));
        assertNotNull(findType(dialog, TableReviewPanel.class));
        var bounds = dialog.getGraphicsConfiguration().getBounds();
        assertTrue(dialog.getWidth() <= bounds.width); assertTrue(dialog.getHeight() <= bounds.height);
        assertTrue(dialog.getWidth() < 1000, "Guidance must not create a very wide window");
    }

    private static JDialog awaitDialog(String title) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var found = new java.util.concurrent.atomic.AtomicReference<JDialog>();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                for (var window : java.awt.Window.getWindows()) if (window instanceof JDialog dialog
                        && title.equals(dialog.getTitle()) && dialog.isShowing()) found.set(dialog);
            });
            if (found.get() != null) return found.get();
            Thread.sleep(10);
        }
        throw new AssertionError("Dialog did not open: " + title);
    }

    private static <T extends Component> T findType(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            T found = findType(child, type); if (found != null) return found;
        }
        return null;
    }

    private static JButton findButton(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) return button;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            JButton found = findButton(child, text); if (found != null) return found;
        }
        return null;
    }

    @Test void sourceChoicesSaveOnlyTriggerBindingsAndSurviveOtherTriggerEditsAndFlowSwitches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlowisePerformanceTest.store = new java.util.concurrent.ConcurrentHashMap<>();
            var repository = new FlowRepository(FlowisePerformanceTest.persistence(), new FlowSerializer(), new PersistenceMigrationService());
            var controller = new FlowWorkspaceController(repository);
            var profiles = new InMemoryAccessRepository();
            profiles.saveAuthProfile(new AuthProfile("alice", "Alice", "", "manual", Map.of("username", "private"), AuthState.empty(), true));
            profiles.saveDataProfile(DataProfile.enabled("data", "Account A", "someone-else", Map.of("id", "1")));
            var flow = FlowisePerformanceTest.flow("one");
            var base = flow.steps().getFirst();
            var rules = List.of(new InjectRule("user", "User", true, InjectRule.Target.REQUEST_HEADER, "X-User",
                    InjectRule.ValueSourceType.VARIABLE, "credentials.username", "", null));
            var step = base.withInjectRules(rules);
            flow = new FlowDefinition(flow.id(), flow.name(), "", true, List.of(step), null, null, null);
            controller.addFlow(flow);
            controller.setActiveFlow(flow); controller.setSelectedStep(step);
            var panel = new TriggerPanel(controller, new AccessUiController(profiles));
            var auth = (JComboBox<?>) find(panel, "triggerAuthProfile");
            var data = (JComboBox<?>) find(panel, "triggerDataProfile");
            var status = (JLabel) find(panel, "triggerInputStatus");
            assertEquals("None", auth.getSelectedItem().toString());
            assertTrue(status.getText().contains("missing"));
            auth.setSelectedIndex(1); data.setSelectedIndex(1);
            assertEquals(new TriggerInputBindings("alice", "data"), controller.selectedStep().triggerRule().inputBindings());
            assertFalse(status.getText().contains("missing"));
            assertFalse(status.getText().contains("private"));
            assertEquals(rules, controller.selectedStep().injectRules());
            JCheckBox enabled = findCheckbox(panel, "Enable auto-trigger for this step");
            enabled.doClick();
            assertTrue(controller.selectedStep().triggerRule().enabled());
            assertEquals(new TriggerInputBindings("alice", "data"), controller.selectedStep().triggerRule().inputBindings());
            var saved = controller.activeFlow();
            var second = FlowisePerformanceTest.flow("two");
            controller.addFlow(second); controller.setActiveFlow(second); controller.setSelectedStep(second.steps().getFirst());
            assertEquals("None", auth.getSelectedItem().toString());
            assertEquals("None", data.getSelectedItem().toString());
            controller.setActiveFlow(saved); controller.setSelectedStep(saved.steps().getFirst());
            assertEquals("Alice", auth.getSelectedItem().toString());
            assertEquals("Account A", data.getSelectedItem().toString());
            profiles.deleteAuthProfile("alice");
            controller.setSelectedStep(controller.selectedStep());
            assertEquals("[missing] alice", auth.getSelectedItem().toString());
            assertTrue(status.getText().contains("not found"));
            assertEquals("alice", controller.selectedStep().triggerRule().inputBindings().authProfileId());
            auth.setSelectedIndex(0);
            assertEquals("", controller.selectedStep().triggerRule().inputBindings().authProfileId());
            assertTrue(status.getText().contains("missing"));
            assertEquals("data", controller.selectedStep().triggerRule().inputBindings().dataProfileId());
            assertFalse(new FlowSerializer().serialize(controller.flows()).contains("private"));
        });
    }

    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            Component found = find(child, name); if (found != null) return found;
        }
        return null;
    }
    private static JCheckBox findCheckbox(Component root, String label) {
        if (root instanceof JCheckBox box && label.equals(box.getText())) return box;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            JCheckBox found = findCheckbox(child, label); if (found != null) return found;
        }
        return null;
    }
}
