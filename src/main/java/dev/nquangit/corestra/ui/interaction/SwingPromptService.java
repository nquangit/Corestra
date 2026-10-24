package dev.nquangit.corestra.ui.interaction;

import dev.nquangit.corestra.interaction.InteractiveInputConfig;
import dev.nquangit.corestra.interaction.InteractiveInputPrompt;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.session.SessionChoice;
import dev.nquangit.corestra.session.SessionChoicePrompt;
import dev.nquangit.corestra.session.SessionTrafficContext;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Serializes operator prompts, bounds queue/dialog waits, and releases waiting requests on unload. */
public final class SwingPromptService implements InteractiveInputPrompt, SessionChoicePrompt, AutoCloseable {
    private final Supplier<Component> owner;
    private final Semaphore gate = new Semaphore(1, true);
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<JDialog> dialogs = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Creates prompts owned by the Burp window containing the supplied component. */
    public SwingPromptService(Supplier<Component> owner) { this.owner = owner; }

    /** Asks for transient text; blank submissions remain in the dialog for correction. */
    @Override public Optional<String> ask(String ruleName, InteractiveInputConfig config) {
        return show("Corestra — Interactive input", config.timeoutSeconds(), () -> {
            JTextField input = config.secret() ? new JPasswordField(32) : new JTextField(32);
            JPanel form = new JPanel(new BorderLayout(0, 8));
            JTextArea prompt = message(config.prompt().isBlank() ? "Enter a value for " + ruleName : config.prompt());
            form.add(prompt, BorderLayout.NORTH);
            form.add(input, BorderLayout.CENTER);
            form.add(new JLabel("Value is used for this injection only. Closing this dialog stops execution."), BorderLayout.SOUTH);
            Supplier<Optional<String>> read = () -> {
                String value;
                if (input instanceof JPasswordField password) {
                    char[] chars = password.getPassword();
                    value = new String(chars);
                    Arrays.fill(chars, '\0');
                } else value = input.getText();
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            };
            return new Prompt<>(form, input, read, "Enter a non-blank value.");
        });
    }

    /** Asks for a session using names/roles only; no credentials or tokens are displayed. */
    @Override public Optional<String> choose(SessionTrafficContext context, List<SessionChoice> choices, int timeoutSeconds) {
        return show("Corestra — Send with session", timeoutSeconds, () -> {
            JComboBox<SessionChoice> choice = new JComboBox<>(choices.toArray(SessionChoice[]::new));
            JPanel form = new JPanel(new BorderLayout(0, 8));
            form.add(message(context.toolTypeName() + " • " + context.method() + " "
                    + context.scheme() + "://" + context.host() + context.path()
                    + "\nChoose an existing session for this request. No login will be run."), BorderLayout.NORTH);
            form.add(choice, BorderLayout.CENTER);
            form.add(new JLabel("Session validity is checked again before sending. Cancel stops the request."), BorderLayout.SOUTH);
            return new Prompt<>(form, choice, () -> Optional.ofNullable((SessionChoice) choice.getSelectedItem())
                    .map(SessionChoice::profileId), "Choose a session.");
        });
    }

    private <T> Optional<T> show(String title, int seconds, Supplier<Prompt<T>> build) {
        if (closed || GraphicsEnvironment.isHeadless()) throw new InteractionAbortedException("Operator UI is unavailable");
        if (SwingUtilities.isEventDispatchThread()) throw new InteractionAbortedException("Interactive execution must run outside the UI thread");
        if (seconds < 1 || seconds > 3600) throw new InteractionAbortedException("Invalid interaction timeout");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<JDialog> displayed = new AtomicReference<>();
        boolean acquired = false;
        pending.add(result);
        try {
            acquired = gate.tryAcquire(seconds, TimeUnit.SECONDS);
            if (!acquired) throw new InteractionAbortedException("Timed out waiting for another interactive prompt");
            if (closed) throw new InteractionAbortedException("Extension unloaded");
            SwingUtilities.invokeLater(() -> {
                if (closed || result.isDone()) return;
                try {
                    Prompt<T> prompt = build.get();
                    Component component = owner.get();
                    Window window = component instanceof Window value ? value : component == null ? null : SwingUtilities.getWindowAncestor(component);
                    JDialog dialog = new JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL);
                    displayed.set(dialog);
                    dialogs.add(dialog);
                    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    JLabel error = new JLabel(" ");
                    JButton ok = StyledButton.success("Continue");
                    JButton cancel = StyledButton.neutral("Cancel");
                    ok.addActionListener(event -> {
                        Optional<T> value;
                        try { value = prompt.read().get(); }
                        catch (RuntimeException exception) {
                            result.completeExceptionally(new InteractionAbortedException("Could not read interactive input"));
                            dialog.dispose(); return;
                        }
                        if (value.isEmpty()) { error.setText(prompt.validationMessage()); return; }
                        result.complete(value.orElseThrow());
                        dialog.dispose();
                    });
                    cancel.addActionListener(event -> dialog.dispose());
                    dialog.addWindowListener(new WindowAdapter() {
                        @Override public void windowClosed(WindowEvent event) {
                            dialogs.remove(dialog);
                            result.completeExceptionally(new InteractionAbortedException("Interaction cancelled"));
                        }
                    });
                    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                    buttons.add(new JLabel("Timeout: " + seconds + "s"));
                    buttons.add(cancel); buttons.add(ok);
                    JPanel content = new JPanel(new BorderLayout(0, 10));
                    content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
                    content.add(prompt.component(), BorderLayout.CENTER);
                    JPanel footer = new JPanel(new BorderLayout());
                    footer.add(error, BorderLayout.CENTER); footer.add(buttons, BorderLayout.SOUTH);
                    content.add(footer, BorderLayout.SOUTH);
                    dialog.setContentPane(content);
                    dialog.getRootPane().setDefaultButton(ok);
                    dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
                    dialog.pack();
                    dialog.setLocationRelativeTo(window);
                    SwingUtilities.invokeLater(() -> prompt.focus().requestFocusInWindow());
                    if (!result.isDone() && !closed) dialog.setVisible(true);
                    else dialog.dispose();
                } catch (RuntimeException exception) {
                    result.completeExceptionally(new InteractionAbortedException("Could not open the interaction dialog"));
                }
            });
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return Optional.of(result.get(remaining, TimeUnit.NANOSECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InteractionAbortedException("Interaction wait interrupted");
        } catch (TimeoutException exception) {
            throw new InteractionAbortedException("Interaction timed out after " + seconds + " seconds");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof InteractionAbortedException aborted) throw aborted;
            throw new InteractionAbortedException("Interaction failed");
        } finally {
            result.completeExceptionally(new InteractionAbortedException("Interaction ended"));
            pending.remove(result);
            SwingUtilities.invokeLater(() -> {
                JDialog dialog = displayed.get();
                if (dialog != null) { dialogs.remove(dialog); dialog.dispose(); }
            });
            if (acquired) gate.release();
        }
    }

    private static JTextArea message(String text) {
        JTextArea area = new JTextArea(text, 3, 48);
        area.setLineWrap(true); area.setWrapStyleWord(true); area.setEditable(false); area.setOpaque(false);
        return area;
    }

    private record Prompt<T>(JComponent component, JComponent focus, Supplier<Optional<T>> read, String validationMessage) { }

    /** Cancels pending waits and disposes all prompts when Corestra unloads. */
    @Override public void close() {
        closed = true;
        pending.forEach(future -> future.completeExceptionally(new InteractionAbortedException("Extension unloaded")));
        SwingUtilities.invokeLater(() -> dialogs.forEach(Window::dispose));
    }
}
