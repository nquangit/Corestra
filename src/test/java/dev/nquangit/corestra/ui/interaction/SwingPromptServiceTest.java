package dev.nquangit.corestra.ui.interaction;

import dev.nquangit.corestra.interaction.InteractiveInputConfig;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.session.SessionChoice;
import dev.nquangit.corestra.session.SessionTrafficContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@ResourceLock("CorestraPromptDialogs")
class SwingPromptServiceTest {
    @Test void maskedInputRejectsBlankThenReturnsExactValue() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SwingPromptService prompts = new SwingPromptService(() -> null)) {
            Future<?> value = executor.submit(() -> prompts.ask("OTP", new InteractiveInputConfig("SMS code", true, 10)));
            JDialog dialog = awaitDialog();
            SwingUtilities.invokeAndWait(() -> button(dialog, "Continue").doClick());
            assertFalse(value.isDone(), "Blank input must leave the popup open");
            SwingUtilities.invokeAndWait(() -> {
                JPasswordField input = find(dialog, JPasswordField.class);
                assertNotNull(input); input.setText(" 123456 "); button(dialog, "Continue").doClick();
            });
            assertEquals(java.util.Optional.of(" 123456 "), value.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test void cancelSelectionReturnsAbortAndDisplaysNoSessionSecrets() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SwingPromptService prompts = new SwingPromptService(() -> null)) {
            var context = new SessionTrafficContext("REPEATER", true, "GET", "api.test", "/api", List.of(), "", "https", 443);
            Future<?> value = executor.submit(() -> prompts.choose(context,
                    List.of(new SessionChoice("alice", "Alice / admin", 5000)), 10));
            JDialog dialog = awaitDialog();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Alice / admin", find(dialog, JComboBox.class).getSelectedItem().toString());
                button(dialog, "Cancel").doClick();
            });
            assertInstanceOf(InteractionAbortedException.class, assertThrows(ExecutionException.class,
                    () -> value.get(5, TimeUnit.SECONDS)).getCause());
        } finally { executor.shutdownNow(); }
    }

    @Test void queuedPromptTimesOutWithoutOpeningAnotherWindowAndUnloadReleasesActiveWait() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SwingPromptService prompts = new SwingPromptService(() -> null);
        try {
            Future<?> first = executor.submit(() -> prompts.ask("first", new InteractiveInputConfig("First", false, 10)));
            awaitDialog();
            Future<?> queued = executor.submit(() -> prompts.ask("queued", new InteractiveInputConfig("Second", false, 1)));
            assertInstanceOf(InteractionAbortedException.class, assertThrows(ExecutionException.class,
                    () -> queued.get(5, TimeUnit.SECONDS)).getCause());
            SwingUtilities.invokeAndWait(() -> assertEquals(1, visibleDialogs().size()));
            prompts.close();
            assertInstanceOf(InteractionAbortedException.class, assertThrows(ExecutionException.class,
                    () -> first.get(5, TimeUnit.SECONDS)).getCause());
        } finally { prompts.close(); executor.shutdownNow(); }
    }

    @Test void displayedPromptTimeoutStopsWaitingAndDisposesDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SwingPromptService prompts = new SwingPromptService(() -> null)) {
            Future<?> waiting = executor.submit(() -> prompts.ask("OTP", new InteractiveInputConfig("SMS", true, 1)));
            awaitDialog();
            assertInstanceOf(InteractionAbortedException.class, assertThrows(ExecutionException.class,
                    () -> waiting.get(5, TimeUnit.SECONDS)).getCause());
            SwingUtilities.invokeAndWait(() -> assertTrue(visibleDialogs().isEmpty()));
        } finally { executor.shutdownNow(); }
    }

    @Test void unavailableClosedOrEdtExecutionCannotSilentlyContinue() throws Exception {
        SwingPromptService prompts = new SwingPromptService(() -> null);
        SwingUtilities.invokeAndWait(() -> assertThrows(InteractionAbortedException.class,
                () -> prompts.ask("OTP", InteractiveInputConfig.defaults())));
        prompts.close();
        assertThrows(InteractionAbortedException.class, () -> prompts.ask("OTP", InteractiveInputConfig.defaults()));
    }

    private static JDialog awaitDialog() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicReference<JDialog> value = new AtomicReference<>();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> value.set(visibleDialogs().stream().findFirst().orElse(null)));
            if (value.get() != null) return value.get();
            Thread.sleep(10);
        }
        throw new AssertionError("Prompt did not open within five seconds");
    }

    private static List<JDialog> visibleDialogs() {
        return java.util.Arrays.stream(Window.getWindows()).filter(JDialog.class::isInstance)
                .map(JDialog.class::cast).filter(dialog -> dialog.isShowing() && dialog.getTitle().startsWith("Corestra —"))
                .toList();
    }

    private static JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(text)) return button;
            if (child instanceof Container nested) { JButton found = button(nested, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T found = find(nested, type); if (found != null) return found; }
        }
        return null;
    }
}
