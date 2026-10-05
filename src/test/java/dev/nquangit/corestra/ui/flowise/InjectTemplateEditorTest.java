package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InjectTemplateEditorTest {
    @Test
    void variablePickerInsertsIntoTemplateAndEditorRoundTripsExistingRule() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<InjectRule> saved = new AtomicReference<>();
            InjectRule existing = new InjectRule("bearer", "Bearer", true, InjectRule.Target.REQUEST_HEADER,
                    "Authorization", InjectRule.ValueSourceType.TEMPLATE, "", "Bearer ",
                    InjectRule.WriteStrategy.OVERWRITE);
            JPanel form = RuleEditorDialog.buildInjectForm(null, null, existing, null, saved::set);
            JComboBox<?> picker = (JComboBox<?>) named(form, "injectVariableKey");
            picker.getEditor().setItem("auth.refreshToken");
            JTextField value = (JTextField) named(form, "injectInlineValue");
            assertTrue(value.isVisible());
            value.setCaretPosition(value.getText().length());
            button(form, "Insert selected variable").doClick();
            assertEquals("Bearer {{auth.refreshToken}}", value.getText());
            button(form, "OK").doClick();
            assertNotNull(saved.get());
            assertEquals(List.of("auth.refreshToken"), saved.get().referencedVariableKeys());
            AtomicReference<InjectRule> edited = new AtomicReference<>();
            button(RuleEditorDialog.buildInjectForm(null, null, saved.get(), null, edited::set), "OK").doClick();
            assertEquals(saved.get(), edited.get());
        });
    }

    @Test
    void invalidTemplateIsShownAsValidationErrorWithoutSaving() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<InjectRule> saved = new AtomicReference<>();
            InjectRule existing = new InjectRule("bearer", "Bearer", true, InjectRule.Target.REQUEST_HEADER,
                    "Authorization", InjectRule.ValueSourceType.TEMPLATE, "", "{{token}}", InjectRule.WriteStrategy.OVERWRITE);
            JPanel form = RuleEditorDialog.buildInjectForm(null, null, existing, null, saved::set);
            ((JTextField) named(form, "injectInlineValue")).setText("Bearer {{auth.refreshToken");
            button(form, "OK").doClick();
            assertNull(saved.get());
            assertTrue(((JLabel) named(form, "injectValidationError")).getText().contains("unclosed"));
        });
    }

    @Test
    void bearerShortcutPreservesTrailingSpaceThroughTransformInputParsing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea chain = new JTextArea("trim");
            button(ValueTransformHelp.controls(chain), "Add Bearer prefix").doClick();
            assertEquals(List.of("trim", "prefix:Bearer "), RuleEditorDialog.transformLines(chain.getText()));
        });
    }

    private static JButton button(Container container, String text) {
        return (JButton) find(container, component -> component instanceof JButton button && text.equals(button.getText()));
    }

    private static Component named(Container container, String name) {
        return find(container, component -> name.equals(component.getName()));
    }

    private static Component find(Container container, java.util.function.Predicate<Component> predicate) {
        for (Component component : container.getComponents()) {
            if (predicate.test(component)) return component;
            if (component instanceof Container child) {
                Component result = find(child, predicate);
                if (result != null) return result;
            }
        }
        return null;
    }
}
