package dev.nquangit.corestra.session;

import dev.nquangit.corestra.interaction.InteractionAbortedException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Coordinates explicit session choice without authenticating or holding profile locks during a popup. */
public final class SessionSelectionService {
    private final SessionManager manager;
    private final Supplier<SessionSelectionConfig> configuration;
    private final SessionChoicePrompt prompt;

    /** Creates a selector using current project settings and an operator prompt. */
    public SessionSelectionService(SessionManager manager, Supplier<SessionSelectionConfig> configuration, SessionChoicePrompt prompt) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    /** Reads the current trigger configuration. */
    public SessionSelectionConfig configuration() { return configuration.get(); }

    /** Explicit operator choice for manual testing, independent of automatic header-trigger configuration. */
    public SessionInjectionPlan chooseExisting(SessionTrafficContext context, int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("Invalid selection timeout");
        if (Thread.currentThread().isInterrupted()) throw new InteractionAbortedException("Session selection interrupted");
        var choices = manager.existingSessionChoices(context);
        if (choices.isEmpty()) throw new InteractionAbortedException("No enabled, unexpired session is available. Run Auth Now first.");
        String id = prompt.choose(context, choices, timeoutSeconds).orElseThrow(() -> new InteractionAbortedException("Session selection cancelled"));
        if (Thread.currentThread().isInterrupted() || choices.stream().noneMatch(choice -> choice.profileId().equals(id)))
            throw new InteractionAbortedException("Session choice is no longer usable");
        return manager.prepareExistingSession(id, context);
    }

    /** Prepares a chosen existing session, or aborts rather than falling back to the request's captured session. */
    public SessionInjectionPlan choose(SessionTrafficContext context, SessionSelectionConfig config) {
        if (!config.matches(context)) throw new InteractionAbortedException("Request is outside session selection conditions");
        if (Thread.currentThread().isInterrupted()) throw new InteractionAbortedException("Session selection interrupted");
        var choices = manager.existingSessionChoices(context);
        if (choices.isEmpty()) throw new InteractionAbortedException("No enabled, unexpired session with applicable injection rules is available. Run Auth Now first.");
        String id = prompt.choose(context, choices, config.timeoutSeconds())
                .orElseThrow(() -> new InteractionAbortedException("Session selection cancelled"));
        if (choices.stream().noneMatch(choice -> choice.profileId().equals(id))) {
            throw new InteractionAbortedException("Session was not in the offered choices");
        }
        if (Thread.currentThread().isInterrupted()) throw new InteractionAbortedException("Session selection interrupted");
        // Disable/change settings while a prompt is open must not silently authorize a send.
        if (!config.equals(configuration.get())) throw new InteractionAbortedException("Session selection settings changed while waiting");
        return manager.prepareExistingSession(id, context);
    }
}
