package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.integration.ManualTestMessages;
import dev.nquangit.corestra.ui.component.WrappedText;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JsonSelectorEditorTest {
    @Test void editorExplainsAllThreeFormatsPreservesModesAndRejectsInvalidQuerySyntax() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var saved = new AtomicReference<ExtractRule>();
            var count = new AtomicInteger();
            var form = RuleEditorDialog.buildExtractForm(step(), rule().withJsonResultMode(ExtractRule.JsonResultMode.ALL), Optional.empty(),
                    result -> { saved.set(result); count.incrementAndGet(); });
            var help = named(form, "jsonSelectorHelp", WrappedText.class);
            assertTrue(help.isVisible()); assertTrue(help.getText().contains("Dot path:"));
            assertTrue(help.getText().contains("no $")); assertTrue(help.getText().contains("pointer:/")); assertTrue(help.getText().contains("$.data.token"));
            assertFalse(help.getText().contains("<html>"));
            var selector = named(form, "extractSelector", JTextField.class);
            var modes = named(form, "jsonResultMode", JComboBox.class);
            assertTrue(modes.isEnabled()); assertEquals(ExtractRule.JsonResultMode.ALL, modes.getSelectedItem());
            button(form, "OK").doClick(); assertEquals(ExtractRule.JsonResultMode.ALL, saved.get().jsonResultMode());
            selector.setText("data.token"); assertFalse(modes.isEnabled());
            selector.setText("pointer:/data/token"); assertFalse(modes.isEnabled());
            selector.setText("$..token"); assertTrue(modes.isEnabled());
            selector.setText("$."); button(form, "OK").doClick();
            assertEquals(1, count.get()); assertTrue(named(form, "extractRuleError", JLabel.class).getText().contains("Invalid JSON selector"));
            named(form, "extractType", JComboBox.class).setSelectedItem(ExtractRule.ExtractorType.HEADER);
            assertFalse(help.isVisible()); assertFalse(modes.isVisible());
        });
    }

    @Test void previewUsesDisplayedResponseOffEdtShowsFullValueAndDoesNotSaveRules() throws Exception {
        String title = "JavaScript title ".repeat(30);
        var response = ManualTestMessages.response("{\"store\":{\"book\":[{\"title\":\"" + title + "\"},{\"title\":\"JavaScript other\"}]}}");
        AtomicBoolean readOffEdt = new AtomicBoolean();
        HttpResponse checked = ManualTestMessages.proxy(HttpResponse.class, (p, m, a) -> {
            if (m.getName().equals("bodyToString")) { assertFalse(SwingUtilities.isEventDispatchThread()); readOffEdt.set(true); }
            return m.invoke(response, a);
        });
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<JPanel> formRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> formRef.set(RuleEditorDialog.buildExtractForm(step(), rule(), Optional.empty(),
                () -> { assertTrue(SwingUtilities.isEventDispatchThread()); return Optional.of(checked); }, saved -> saves.incrementAndGet())));
        JPanel form = formRef.get();
        SwingUtilities.invokeAndWait(() -> button(form, "Test / Preview JSON").doClick());
        awaitPreview(form);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(named(form, "extractionPreview", JTextArea.class).getText().contains("2 values"));
            named(form, "jsonResultMode", JComboBox.class).setSelectedItem(ExtractRule.JsonResultMode.FIRST);
            button(form, "Test / Preview JSON").doClick();
        });
        awaitPreview(form);
        SwingUtilities.invokeAndWait(() -> {
            String preview = named(form, "extractionPreview", JTextArea.class).getText();
            assertTrue(preview.endsWith(title), preview); assertTrue(readOffEdt.get()); assertEquals(0, saves.get());
        });
    }

    @Test void editingSelectorWhilePreviewRunsCannotPublishAnOldResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpResponse base = ManualTestMessages.response("{\"store\":{\"book\":[{\"title\":\"JavaScript stale\"}]}}");
        HttpResponse blocked = ManualTestMessages.proxy(HttpResponse.class, (p, m, a) -> {
            if (m.getName().equals("bodyToString")) { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return m.invoke(base, a);
        });
        AtomicReference<JPanel> formRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var form = RuleEditorDialog.buildExtractForm(step(), rule(), Optional.empty(), () -> Optional.of(blocked), saved -> fail("Preview must not save"));
                formRef.set(form); button(form, "Test / Preview JSON").doClick();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> named(formRef.get(), "extractSelector", JTextField.class).setText("$.newValue"));
            release.countDown(); awaitPreview(formRef.get());
            SwingUtilities.invokeAndWait(() -> assertEquals("", named(formRef.get(), "extractionPreview", JTextArea.class).getText()));
        } finally { release.countDown(); }
    }

    private static ExtractRule rule() {
        return new ExtractRule("json", "Title", true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.JSON_PATH,
                "$.store.book[?(@.title =~ /.*JavaScript.*/i)].title", "title", List.of());
    }
    private static FlowStep step() {
        return new FlowStep("s", "Step", true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW)
                .withLastResponseRaw(Base64.getEncoder().encodeToString("HTTP/1.1 200 OK\r\n\r\n{\"saved\":\"old\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void awaitPreview(JPanel form) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicBoolean ready = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready.set(button(form, "Test / Preview JSON").isEnabled()));
            if (ready.get()) return;
            Thread.sleep(10);
        }
        fail("Preview did not complete");
    }
    private static <T extends Component> T named(Component root, String name, Class<T> type) {
        if (name.equals(root.getName()) && type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            T found = named(child, name, type); if (found != null) return found;
        }
        return null;
    }
    private static JButton button(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) return button;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            JButton found = button(child, text); if (found != null) return found;
        }
        return null;
    }
}
