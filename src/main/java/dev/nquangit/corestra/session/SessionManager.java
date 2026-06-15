package dev.nquangit.corestra.session;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates project-level session profile selection, state updates, and auth refresh.
 */
public final class SessionManager {
    private static final Pattern SESSION_PLACEHOLDER = Pattern.compile("\\{\\{\\s*session\\.([A-Za-z0-9_-]+)\\s*}}");

    private final SessionProfileRepository repository;
    private final Supplier<List<FlowDefinition>> flowsSupplier;
    private final FlowEngine flowEngine;
    private final ExtractionService extractionService;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<SessionProfileChangeEvent>> profileChangeListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Creates a session manager.
     *
     * @param repository session profile repository
     * @param flowsSupplier current Flowise flows
     * @param flowEngine flow execution engine used for auth flows
     * @param extractionService extractor used by response-side session rules
     * @param clock runtime clock
     */
    public SessionManager(
            SessionProfileRepository repository,
            Supplier<List<FlowDefinition>> flowsSupplier,
            FlowEngine flowEngine,
            ExtractionService extractionService,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.flowsSupplier = Objects.requireNonNull(flowsSupplier, "flowsSupplier");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.extractionService = Objects.requireNonNull(extractionService, "extractionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Loads session profiles.
     *
     * @return immutable profiles
     */
    public List<SessionProfile> profiles() {
        return repository.loadProfiles();
    }

    /**
     * Adds a listener for profile and state changes.
     *
     * @param listener listener to add
     */
    public void addProfileChangeListener(Consumer<SessionProfileChangeEvent> listener) {
        profileChangeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a previously registered profile change listener.
     *
     * @param listener listener to remove
     */
    public void removeProfileChangeListener(Consumer<SessionProfileChangeEvent> listener) {
        profileChangeListeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Saves one profile.
     *
     * @param profile profile to save
     * @return saved profile
     */
    public SessionProfile saveProfile(SessionProfile profile) {
        SessionProfile saved = repository.saveProfile(profile);
        notifyProfileChanged(new SessionProfileChangeEvent(saved.id(), SessionProfileChangeType.PROFILE_SAVED));
        return saved;
    }

    /**
     * Deletes a non-system profile.
     *
     * @param id profile id
     * @return true when deleted
     */
    public boolean deleteProfile(String id) {
        boolean deleted = repository.deleteProfile(id);
        if (deleted) {
            notifyProfileChanged(new SessionProfileChangeEvent(id, SessionProfileChangeType.PROFILE_DELETED));
        }
        return deleted;
    }

    /**
     * Clears one profile's state.
     *
     * @param profileId profile id
     * @return updated profile when present
     */
    public Optional<SessionProfile> clearState(String profileId) {
        Optional<SessionProfile> profile = repository.profile(profileId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        SessionProfile updated = repository.saveProfile(profile.orElseThrow().withState(SessionState.empty()));
        notifyProfileChanged(new SessionProfileChangeEvent(updated.id(), SessionProfileChangeType.STATE_CLEARED));
        return Optional.of(updated);
    }

    /**
     * Selects a profile for the supplied traffic context.
     *
     * @param context traffic context
     * @return selected profile and warnings
     */
    public SessionProfileSelection selectProfile(SessionTrafficContext context) {
        Objects.requireNonNull(context, "context");
        List<SessionProfile> profiles = repository.loadProfiles();
        SessionProfile general = null;
        List<SessionProfile> matches = new ArrayList<>();
        for (SessionProfile profile : profiles) {
            if (profile.isGeneral()) {
                general = profile;
                continue;
            }
            if (profile.matches(context.burpInScope(), context.method(), context.host(), context.path(),
                    context.headers(), context.body())) {
                matches.add(profile);
            }
        }
        if (!matches.isEmpty()) {
            ArrayList<String> warnings = new ArrayList<>();
            if (matches.size() > 1) {
                warnings.add("Multiple session profiles matched; using first profile '" + matches.getFirst().name() + "'.");
            }
            return SessionProfileSelection.of(matches.getFirst(), warnings);
        }
        if (general != null && general.matches(context.burpInScope(), context.method(), context.host(), context.path(),
                context.headers(), context.body())) {
            return SessionProfileSelection.of(general, List.of());
        }
        return SessionProfileSelection.none();
    }

    /**
     * Runs an auth flow on demand when the selected profile has missing or expired state.
     *
     * @param profile selected profile
     * @param context triggering traffic context
     * @return current profile state after auth attempt
     */
    public SessionProfile ensureAuthenticated(SessionProfile profile, SessionTrafficContext context) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(context, "context");
        if (profile.authConfig().mainAuthFlowId().isBlank()) {
            return profile;
        }
        long now = clock.millis();
        if (!profile.state().missingOrExpired(now)) {
            return profile;
        }
        if (!profile.authConfig().matchesTrigger(context.method(), context.host(), context.path(),
                context.headers(), context.body())) {
            return profile;
        }
        return runAuthFlowLocked(profile.id(), false);
    }

    /**
     * Forces a profile's main auth flow to run, used by the scheduler and manual UI actions.
     *
     * @param profileId profile id
     * @return current profile state after auth attempt
     */
    public Optional<SessionProfile> forceAuthenticate(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(runAuthFlowLocked(profileId.strip(), true));
    }

    /**
     * Applies enabled extraction rules and immediately updates the selected profile state.
     *
     * @param profile selected profile
     * @param context traffic context
     * @param requestSnapshot request snapshot
     * @param responseSnapshot response snapshot
     * @return updated profile
     */
    public SessionProfile applyExtractionRules(
            SessionProfile profile,
            SessionTrafficContext context,
            HttpMessageSnapshot requestSnapshot,
            HttpMessageSnapshot responseSnapshot) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(context, "context");
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        for (SessionExtractionRule rule : profile.extractionRules()) {
            if (!rule.enabled() || !rule.allowsTool(context.toolTypeName())) {
                continue;
            }
            ExtractionResult result = extractionService.extract(rule.toExtractRule(), requestSnapshot, responseSnapshot);
            if (result.isSuccess() && result.value() != null && !result.value().isEmpty()) {
                updates.put(rule.targetKey(), result.value());
            }
        }
        if (updates.isEmpty()) {
            return profile;
        }
        SessionProfile updated = profile.withState(profile.state().withExtractedValues(updates, clock.millis()));
        SessionProfile saved = repository.saveProfile(updated);
        notifyProfileChanged(new SessionProfileChangeEvent(saved.id(), SessionProfileChangeType.STATE_UPDATED));
        return saved;
    }

    /**
     * Creates rendered static Flowise injection rules for one profile and tool.
     *
     * @param profile selected profile
     * @param toolTypeName Montoya tool type name
     * @return injection rules with rendered static values
     */
    public List<InjectRule> renderedInjectionRules(SessionProfile profile, String toolTypeName) {
        Objects.requireNonNull(profile, "profile");
        ArrayList<InjectRule> rules = new ArrayList<>();
        for (SessionInjectionRule rule : profile.injectionRules()) {
            if (!rule.enabled() || !rule.allowsTool(toolTypeName)) {
                continue;
            }
            renderSessionTemplate(rule.valueTemplate(), profile.state()).ifPresent(value ->
                    rules.add(rule.toInjectRule(value)));
        }
        return List.copyOf(rules);
    }

    /**
     * Renders {@code {{session.key}}} placeholders from one profile state.
     *
     * @param template source template
     * @param state session state
     * @return rendered value, or empty when a referenced session key is missing
     */
    public Optional<String> renderSessionTemplate(String template, SessionState state) {
        Objects.requireNonNull(state, "state");
        String source = template == null ? "" : template;
        Matcher matcher = SESSION_PLACEHOLDER.matcher(source);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = state.values().get(key);
            if (value == null) {
                return Optional.empty();
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return Optional.of(rendered.toString());
    }

    private SessionProfile runAuthFlowLocked(String profileId, boolean force) {
        ReentrantLock lock = locks.computeIfAbsent(profileId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Optional<SessionProfile> current = repository.profile(profileId);
            if (current.isEmpty()) {
                return SessionProfile.general();
            }
            SessionProfile profile = current.orElseThrow();
            if (profile.authConfig().mainAuthFlowId().isBlank()) {
                return profile;
            }
            long now = clock.millis();
            if (!force && !profile.state().missingOrExpired(now)) {
                return profile;
            }
            Optional<FlowDefinition> flow = flowsSupplier.get().stream()
                    .filter(candidate -> candidate.id().equals(profile.authConfig().mainAuthFlowId()))
                    .findFirst();
            if (flow.isEmpty()) {
                return profile;
            }
            SessionGuard.enter();
            LinkedHashMap<String, String> updates;
            try {
                updates = runAuthFlow(flow.orElseThrow(), profile);
            } finally {
                SessionGuard.exit();
            }
            if (updates.isEmpty()) {
                return profile;
            }
            SessionProfile updated = profile.withState(profile.state().withAuthenticatedValues(
                    updates, now, profile.authConfig().ttlSeconds()));
            SessionProfile saved = repository.saveProfile(updated);
            notifyProfileChanged(new SessionProfileChangeEvent(saved.id(), SessionProfileChangeType.STATE_UPDATED));
            return saved;
        } finally {
            lock.unlock();
        }
    }

    private void notifyProfileChanged(SessionProfileChangeEvent event) {
        for (Consumer<SessionProfileChangeEvent> listener : profileChangeListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // Session change notifications must not break traffic handling or persistence.
            }
        }
    }

    private LinkedHashMap<String, String> runAuthFlow(FlowDefinition flow, SessionProfile profile) {
        long now = clock.millis();
        ExecutionContext context = ExecutionContext.start(
                UUID.randomUUID().toString(),
                flow.id(),
                Map.of("sessionProfileId", profile.id(), "sessionAuthFlow", "true"),
                now);
        for (Map.Entry<String, String> entry : profile.state().values().entrySet()) {
            context = context.withVariable("session." + entry.getKey(), entry.getValue());
        }

        ExecutionResult result = flowEngine.execute(flow, context);
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        result.finalContext().ifPresent(finalContext ->
                finalContext.variables().forEach((key, value) -> collectSessionVariable(updates, key, value)));
        result.stepRecords().forEach(record -> {
            record.extractedVariables().forEach((key, value) -> collectSessionVariable(updates, key, value));
            applyAuthFlowExtractionRules(profile, record).forEach(updates::put);
        });
        return updates;
    }

    private Map<String, String> applyAuthFlowExtractionRules(SessionProfile profile, StepExecutionRecord record) {
        if (profile.extractionRules().isEmpty()) {
            return Map.of();
        }
        HttpMessageSnapshot requestSnapshot = snapshotFromRaw(record.renderedRequest(), false, record.responseStatusCode());
        HttpMessageSnapshot responseSnapshot = snapshotFromRaw(record.receivedResponse(), true, record.responseStatusCode());
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        for (SessionExtractionRule rule : profile.extractionRules()) {
            if (!rule.enabled() || !rule.allowsTool("EXTENSIONS")) {
                continue;
            }
            ExtractionResult result = extractionService.extract(rule.toExtractRule(), requestSnapshot, responseSnapshot);
            if (result.isSuccess() && result.value() != null && !result.value().isEmpty()) {
                updates.put(rule.targetKey(), result.value());
            }
        }
        return updates;
    }

    private static void collectSessionVariable(Map<String, String> updates, String key, String value) {
        if (key == null || value == null || value.isEmpty() || !key.startsWith("session.")) {
            return;
        }
        updates.put(SessionState.normalizeSessionKey(key), value);
    }

    private static HttpMessageSnapshot snapshotFromRaw(String raw, boolean response, int statusCode) {
        String source = raw == null ? "" : raw;
        int split = source.indexOf("\r\n\r\n");
        int separatorLength = 4;
        if (split < 0) {
            split = source.indexOf("\n\n");
            separatorLength = 2;
        }
        String head = split >= 0 ? source.substring(0, split) : source;
        String body = split >= 0 ? source.substring(split + separatorLength) : "";
        String[] lines = head.split("\\r?\\n");
        String statusLine = lines.length > 0 && !lines[0].isBlank()
                ? lines[0]
                : response ? "HTTP/1.1 " + statusCode : "";
        ArrayList<String> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                headers.add(lines[i]);
            }
        }
        return new HttpMessageSnapshot(statusLine, headers, body);
    }
}
