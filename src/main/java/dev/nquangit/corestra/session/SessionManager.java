package dev.nquangit.corestra.session;

import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureResult;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureStatus;
import dev.nquangit.corestra.access.auth.runtime.AuthInjectionRuleRenderer;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates project-level session profile selection, state updates, and auth refresh.
 */
public final class SessionManager {
    private static final Pattern SESSION_OR_AUTH_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*(session|auth)\\.([A-Za-z0-9_.-]+)\\s*}}");

    private final SessionProfileRepository repository;
    private final Supplier<List<FlowDefinition>> flowsSupplier;
    private final FlowEngine flowEngine;
    private final ExtractionService extractionService;
    private final Clock clock;
    private final InMemoryAccessRepository accessRepository;
    private final AuthManager authManager;
    private final AuthInjectionRuleRenderer authInjectionRuleRenderer;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> authenticationGenerations = new ConcurrentHashMap<>();
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
        this(repository, flowsSupplier, flowEngine, extractionService, clock, null, null,
                new AuthInjectionRuleRenderer());
    }

    /**
     * Creates a session manager that can reuse shared Access Auth Profiles.
     *
     * @param repository session profile repository
     * @param flowsSupplier current Flowise flows
     * @param flowEngine flow execution engine used for standalone auth flows
     * @param extractionService extractor used by response-side session rules
     * @param clock runtime clock
     * @param accessRepository shared Access Testing repository
     * @param authManager shared authentication runtime
     * @param authInjectionRuleRenderer renderer for linked provider injection rules
     */
    public SessionManager(
            SessionProfileRepository repository,
            Supplier<List<FlowDefinition>> flowsSupplier,
            FlowEngine flowEngine,
            ExtractionService extractionService,
            Clock clock,
            InMemoryAccessRepository accessRepository,
            AuthManager authManager,
            AuthInjectionRuleRenderer authInjectionRuleRenderer) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.flowsSupplier = Objects.requireNonNull(flowsSupplier, "flowsSupplier");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.extractionService = Objects.requireNonNull(extractionService, "extractionService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accessRepository = accessRepository;
        this.authManager = authManager;
        this.authInjectionRuleRenderer = Objects.requireNonNull(authInjectionRuleRenderer,
                "authInjectionRuleRenderer");
    }

    /** Returns enabled and disabled shared Auth Profiles available for Session bindings. */
    public List<AuthProfile> linkedAuthProfiles() {
        return accessRepository == null ? List.of() : accessRepository.authProfiles();
    }

    /** Returns whether this runtime was wired with shared authentication support. */
    public boolean linkedAuthAvailable() {
        return accessRepository != null && authManager != null;
    }

    /** Returns whether a profile has either linked or standalone authentication configured. */
    public boolean hasAuthentication(SessionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return profile.usesLinkedAuthProfile() || !profile.authConfig().mainAuthFlowId().isBlank();
    }

    /**
     * Loads session profiles.
     *
     * @return immutable profiles
     */
    public List<SessionProfile> profiles() {
        return repository.loadProfiles();
    }

    Optional<SessionProfile> profile(String profileId) {
        return repository.profile(profileId);
    }

    ScheduledAuthInfo scheduledAuthInfo(SessionProfile profile) {
        if (!profile.usesLinkedAuthProfile() || accessRepository == null) {
            return new ScheduledAuthInfo("Standalone", "Authentication", profile.authConfig().mainAuthFlowId(),
                    profile.state(), 0L);
        }
        AuthProfile actor = accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null);
        AuthProvider provider = actor == null ? null : accessRepository.authProvider(actor.providerId()).orElse(null);
        boolean keepAlive = provider != null && provider.keepAlive().enabled();
        return new ScheduledAuthInfo(actor == null ? profile.linkedAuthProfileId() : actor.name(),
                keepAlive ? "Keep-alive" : "Authentication",
                provider == null ? "" : keepAlive ? provider.keepAlive().flowId() : provider.loginFlowId(),
                keepAlive && actor != null ? linkedState(actor) : profile.state(),
                authManager == null ? 0L : authManager.maintenanceRetryAtEpochMillis(profile.linkedAuthProfileId()));
    }

    record ScheduledAuthInfo(String actorName, String mode, String flowId, SessionState state, long retryAt) { }

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
     * Clears every Session Handler profile while waiting for in-flight authentication runs.
     * The repository exposes a fresh, non-persisted General profile afterwards.
     */
    public void clearAllProfiles() {
        List<String> ids = repository.loadProfiles().stream().map(SessionProfile::id).sorted().toList();
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (String id : ids) {
                ReentrantLock lock = locks.computeIfAbsent(id, ignored -> new ReentrantLock());
                lock.lock();
                acquired.add(lock);
            }
            repository.clear();
            ids.forEach(id -> notifyProfileChanged(
                    new SessionProfileChangeEvent(id, SessionProfileChangeType.PROFILE_DELETED)));
        } finally {
            for (int index = acquired.size() - 1; index >= 0; index--) {
                acquired.get(index).unlock();
            }
        }
    }

    /**
     * Clears one profile's state.
     *
     * @param profileId profile id
     * @return updated profile when present
     */
    public Optional<SessionProfile> clearState(String profileId) {
        ReentrantLock lock = lockFor(profileId);
        lock.lock();
        try {
            Optional<SessionProfile> current = repository.profile(profileId);
            current.filter(SessionProfile::usesLinkedAuthProfile).ifPresent(this::clearLinkedAuthState);
            Optional<SessionProfile> updated = repository.updateState(profileId, ignored -> SessionState.empty());
            updated.ifPresent(saved -> {
                advanceAuthenticationGeneration(saved.id());
                notifyProfileChanged(new SessionProfileChangeEvent(
                        saved.id(), SessionProfileChangeType.STATE_CLEARED));
            });
            return updated;
        } finally {
            lock.unlock();
        }
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
            if (profile.matches(context)) {
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
        if (general != null && general.matches(context)) {
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
        if (!hasAuthentication(profile)) {
            return profile;
        }
        if (!profile.authConfig().matchesTrigger(context)) {
            return profile;
        }
        if (profile.usesLinkedAuthProfile()) {
            return runAuthFlowLocked(profile.id(), AuthRunMode.ON_DEMAND);
        }
        long now = clock.millis();
        if (!profile.state().missingOrExpired(now)) {
            return profile;
        }
        return runAuthFlowLocked(profile.id(), AuthRunMode.ON_DEMAND);
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
        return Optional.of(runAuthFlowLocked(profileId.strip(), AuthRunMode.FORCED));
    }

    /**
     * Forces authentication and preserves the outcome and reason for an interactive caller.
     *
     * @param profileId session profile id
     * @return detailed authentication result
     */
    public SessionAuthenticationResult forceAuthenticateWithResult(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return SessionAuthenticationResult.failed(
                    "", "Session Profile id is blank.", Optional.empty(), List.of());
        }
        return runAuthFlowLockedWithResult(profileId.strip(), AuthRunMode.FORCED);
    }

    /**
     * Refreshes an enabled scheduled profile if it is still due after acquiring its auth lock.
     *
     * @param profileId profile id
     * @return current profile state after the refresh check
     */
    public SessionProfile refreshScheduledProfile(String profileId) {
        return refreshScheduledProfileWithResult(profileId).profile().orElseGet(SessionProfile::general);
    }

    SessionAuthenticationResult refreshScheduledProfileWithResult(String profileId) {
        return runAuthFlowLockedWithResult(profileId, AuthRunMode.SCHEDULED);
    }

    /** Runs maintenance immediately, preferring a configured keep-alive flow for linked actors. */
    public SessionAuthenticationResult maintainAuthenticationWithResult(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return SessionAuthenticationResult.failed("", "Session Profile id is blank.", Optional.empty(), List.of());
        }
        return runAuthFlowLockedWithResult(profileId.strip(), AuthRunMode.MAINTENANCE);
    }

    /**
     * Waits for any in-flight authentication of the selected profile, reloads its latest state,
     * performs an on-demand refresh when required, and renders injection rules atomically.
     *
     * <p>The lock is released before the caller sends the HTTP request. This prevents a request
     * from using a stale pre-refresh snapshot without serializing unrelated network traffic.</p>
     *
     * @param selectedProfile profile selected for the outgoing request
     * @param context outgoing request context
     * @return rules and authentication generation captured from one consistent state
     */
    public SessionInjectionPlan prepareInjectionRules(
            SessionProfile selectedProfile,
            SessionTrafficContext context) {
        Objects.requireNonNull(selectedProfile, "selectedProfile");
        Objects.requireNonNull(context, "context");
        ReentrantLock lock = lockFor(selectedProfile.id());
        lock.lock();
        try {
            SessionProfile current = repository.profile(selectedProfile.id()).orElse(null);
            if (current == null) {
                return new SessionInjectionPlan(selectedProfile.id(),
                        authenticationGeneration(selectedProfile.id()), List.of());
            }
            SessionProfile ready = ensureAuthenticated(current, context);
            return new SessionInjectionPlan(
                    ready.id(), authenticationGeneration(ready.id()),
                    renderedInjectionRules(ready, context.toolTypeName()), authenticationRevision(ready));
        } finally {
            lock.unlock();
        }
    }

    /** Lists currently usable existing sessions; ordinary profile scope is overridden by explicit choice. */
    public List<SessionChoice> existingSessionChoices(SessionTrafficContext context) {
        ArrayList<SessionChoice> choices = new ArrayList<>();
        for (SessionProfile profile : repository.loadProfiles()) {
            ReentrantLock lock = lockFor(profile.id());
            if (!lock.tryLock()) continue;
            try {
                SessionProfile current = repository.profile(profile.id()).orElse(null);
                ExistingSessionSnapshot snapshot = existingUsableState(current, context);
                if (snapshot == null) continue;
                String label = current.name() + " [" + current.id() + "]";
                if (current.usesLinkedAuthProfile() && accessRepository != null) {
                    AuthProfile actor = accessRepository.authProfile(current.linkedAuthProfileId()).orElse(null);
                    if (actor != null) label += " — " + actor.name() + (actor.role().isBlank() ? "" : " / " + actor.role());
                }
                choices.add(new SessionChoice(current.id(), label, snapshot.state().expiresAtEpochMillis()));
            } finally { lock.unlock(); }
        }
        return List.copyOf(choices);
    }

    /** Revalidates the chosen existing session and captures its rules atomically, without re-login. */
    public SessionInjectionPlan prepareExistingSession(String id, SessionTrafficContext context) {
        ReentrantLock lock = lockFor(id);
        if (!lock.tryLock()) throw new InteractionAbortedException("Selected session is being maintained; try again after it finishes");
        try {
            SessionProfile current = repository.profile(id).orElse(null);
            ExistingSessionSnapshot snapshot = existingUsableState(current, context);
            if (snapshot == null) {
                throw new InteractionAbortedException("Selected session is disabled, expired, or no longer usable");
            }
            return new SessionInjectionPlan(id, authenticationGeneration(id), snapshot.rules(), authenticationRevision(current));
        } finally { lock.unlock(); }
    }

    /** Captures an existing session consistently while waiting briefly for harmless keep-alive maintenance. */
    public SessionInjectionPlan prepareBoundSession(String id, SessionTrafficContext context) {
        return prepareSessionSnapshot(id, context, true);
    }

    /** Prepares a complete scoped plan, preserving explicit write strategies without binding another request. */
    public SessionInjectionPlan prepareScopedSession(String id, SessionTrafficContext context) {
        return prepareSessionSnapshot(id, context, false);
    }

    private SessionInjectionPlan prepareSessionSnapshot(String id, SessionTrafficContext context, boolean binding) {
        ReentrantLock lock = lockFor(id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) throw new SessionBindingException("Session maintenance did not finish within five seconds");
            SessionProfile profile = repository.profile(id).orElse(null);
            if (profile == null || !profile.enabled()) throw new SessionBindingException("Bound Session Profile is disabled or missing");
            if (profile.usesLinkedAuthProfile()) {
                if (authManager == null || accessRepository == null) throw new SessionBindingException("Shared authentication is unavailable");
                return authManager.inspectExistingProfile(profile.linkedAuthProfileId(), actor -> boundPlan(profile, context, actor, binding));
            }
            return boundPlan(profile, context, null, binding);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SessionBindingException("Session validation interrupted");
        } finally { if (acquired) lock.unlock(); }
    }

    private SessionInjectionPlan boundPlan(SessionProfile profile, SessionTrafficContext context, AuthProfile actor, boolean binding) {
        ExistingSessionSnapshot snapshot = existingUsableState(profile, context, actor, true, binding);
        if (snapshot == null) throw new SessionBindingException("Bound session is expired or its injection rules are incomplete");
        if (binding && snapshot.rules().stream().anyMatch(rule -> rule.target() == InjectRule.Target.REQUEST_RAW_BODY_APPEND
                || rule.target() == InjectRule.Target.REQUEST_REGEX_REPLACEMENT
                || rule.target() == InjectRule.Target.REQUEST_FULL_REGEX_REPLACEMENT)) {
            throw new SessionBindingException("Session binding requires repeatable injection targets; use Header, Cookie, Query, Form, JSON or Raw Body Replace");
        }
        return new SessionInjectionPlan(profile.id(), authenticationGeneration(profile.id()), snapshot.rules(), authenticationRevision(profile));
    }

    private String authenticationRevision(SessionProfile profile) {
        String actorId = profile.linkedAuthProfileId();
        String providerId = actorId.isEmpty() || accessRepository == null ? ""
                : accessRepository.authProfile(actorId).map(AuthProfile::providerId).orElse("");
        return actorId.length() + ":" + actorId + "|" + providerId.length() + ":" + providerId + "|"
                + (actorId.isEmpty() || authManager == null ? 0 : authManager.authenticationEpoch(actorId));
    }

    private ExistingSessionSnapshot existingUsableState(SessionProfile profile, SessionTrafficContext context) {
        AuthProfile actor = profile != null && profile.usesLinkedAuthProfile() && accessRepository != null
                ? accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null) : null;
        return existingUsableState(profile, context, actor, false);
    }

    private ExistingSessionSnapshot existingUsableState(SessionProfile profile, SessionTrafficContext context,
                                                       AuthProfile actor, boolean holdsActorLock) {
        return existingUsableState(profile, context, actor, holdsActorLock, true);
    }

    private ExistingSessionSnapshot existingUsableState(SessionProfile profile, SessionTrafficContext context,
                                                       AuthProfile actor, boolean holdsActorLock, boolean binding) {
        if (profile == null || !profile.enabled()) return null;
        SessionState state = profile.state();
        AuthProvider provider = null;
        if (profile.usesLinkedAuthProfile()) {
            if (accessRepository == null) return null;
            if (actor == null || !actor.enabled() || authManager == null || (!holdsActorLock && authManager.isAuthenticationInProgress(actor.id()))) return null;
            if (!AuthProfile.MANUAL_PROVIDER_ID.equals(actor.providerId())) {
                provider = accessRepository.authProvider(actor.providerId()).orElse(null);
                if (provider == null || !provider.enabled()) return null;
            }
            state = linkedState(actor);
        }
        if (state.missingOrExpired(clock.millis()) && (binding || hasAuthentication(profile) || !state.values().isEmpty())) return null;
        List<InjectRule> rules = renderedInjectionRules(profile, context.toolTypeName(), actor, provider);
        if (rules.isEmpty() || rules.stream().anyMatch(rule -> rule.staticValue().isBlank()
                || (binding && rule.writeStrategy() != InjectRule.WriteStrategy.OVERWRITE))) return null;
        // Reject partial plans rather than retaining one of the original request's auth fields.
        for (SessionInjectionRule local : profile.injectionRules()) {
            if (local.enabled() && local.allowsTool(context.toolTypeName())
                    && rules.stream().noneMatch(rule -> rule.id().equals(local.id()))) return null;
        }
        if (provider != null) {
            for (var rule : provider.injectionRules()) {
                if (rule.enabled() && rules.stream().noneMatch(rendered -> rendered.id().equals("linked-provider-" + rule.id()))) return null;
            }
        }
        return new ExistingSessionSnapshot(state, rules);
    }

    private record ExistingSessionSnapshot(SessionState state, List<InjectRule> rules) { }

    /**
     * Applies response extraction only when the response belongs to the current authentication
     * generation. Responses sent with an older session are ignored after a refresh completes.
     *
     * @param profileId selected profile id captured before sending
     * @param expectedAuthenticationGeneration generation captured before sending
     * @param context request context
     * @param requestSnapshot request snapshot
     * @param responseSnapshot response snapshot
     * @return current or updated profile when it still exists
     */
    public Optional<SessionProfile> applyExtractionRulesIfCurrent(
            String profileId,
            long expectedAuthenticationGeneration,
            SessionTrafficContext context,
            HttpMessageSnapshot requestSnapshot,
            HttpMessageSnapshot responseSnapshot) {
        Objects.requireNonNull(context, "context");
        ReentrantLock lock = lockFor(profileId);
        lock.lock();
        try {
            Optional<SessionProfile> current = repository.profile(profileId);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            if (authenticationGeneration(profileId) != expectedAuthenticationGeneration) {
                return current;
            }
            return Optional.of(applyExtractionRules(
                    current.orElseThrow(), context, requestSnapshot, responseSnapshot));
        } finally {
            lock.unlock();
        }
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
        if (profile.usesLinkedAuthProfile() && updateLinkedAuthState(profile, updates)) {
            SessionProfile latest = synchronizeLinkedStateMarker(profile, false);
            notifyProfileChanged(new SessionProfileChangeEvent(latest.id(), SessionProfileChangeType.STATE_UPDATED));
            return latest;
        }
        Optional<SessionProfile> saved = repository.updateState(profile.id(),
                state -> state.withExtractedValues(updates, clock.millis()));
        saved.ifPresent(updated -> notifyProfileChanged(
                new SessionProfileChangeEvent(updated.id(), SessionProfileChangeType.STATE_UPDATED)));
        return saved.orElse(profile);
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
        AuthProfile actor = profile.usesLinkedAuthProfile() && accessRepository != null
                ? accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null) : null;
        AuthProvider provider = actor == null ? null : accessRepository.authProvider(actor.providerId()).orElse(null);
        return renderedInjectionRules(profile, toolTypeName, actor, provider);
    }

    private List<InjectRule> renderedInjectionRules(SessionProfile profile, String toolTypeName,
                                                   AuthProfile actor, AuthProvider provider) {
        ArrayList<InjectRule> rules = new ArrayList<>();
        SessionState selectedTemplateState = profile.state();
        if (actor != null) {
            rules.addAll(authInjectionRuleRenderer.render(actor, provider));
            selectedTemplateState = linkedState(actor);
        }
        SessionState templateState = selectedTemplateState;
        for (SessionInjectionRule rule : profile.injectionRules()) {
            if (!rule.enabled() || !rule.allowsTool(toolTypeName)) {
                continue;
            }
            renderProfileTemplate(rule.valueTemplate(), templateState, profile.usesLinkedAuthProfile()).ifPresent(value ->
                    rules.add(rule.toInjectRule(value)));
        }
        return List.copyOf(rules);
    }

    /**
     * Returns variable templates available to local Session Handler injection rules.
     * Linked profiles use the canonical {@code auth} namespace; standalone profiles use {@code session}.
     */
    public List<String> availableInjectionVariableTemplates(SessionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        LinkedHashMap<String, String> templates = new LinkedHashMap<>();
        if (!profile.usesLinkedAuthProfile() || accessRepository == null) {
            profile.state().values().keySet().forEach(key -> addTemplate(templates, "session", key));
            return List.copyOf(templates.keySet());
        }
        AuthProfile authProfile = accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null);
        if (authProfile == null) {
            return List.of();
        }
        AuthProvider provider = accessRepository.authProvider(authProfile.providerId()).orElse(null);
        if (provider != null) {
            new AuthProviderFlowAnalyzer().analyze(provider, flowsSupplier.get()).outputKeys()
                    .forEach(key -> addTemplate(templates, "auth", key));
        }
        authProfile.state().variables().keySet().forEach(key -> addTemplate(templates, "auth", key));
        authProfile.state().headers().keySet().forEach(key -> addTemplate(templates, "auth", "header." + key));
        authProfile.state().cookies().keySet().forEach(key -> addTemplate(templates, "auth", "cookie." + key));
        return List.copyOf(templates.keySet());
    }

    /** Returns values displayed in the State tab, including shared actor state when linked. */
    public Map<String, String> displayStateValues(SessionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.usesLinkedAuthProfile() || accessRepository == null) {
            return profile.state().values();
        }
        AuthProfile authProfile = accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null);
        if (authProfile == null) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        authProfile.state().variables().forEach((key, value) -> values.put("auth." + key, value));
        authProfile.state().headers().forEach((key, value) -> values.put("auth.header." + key, value));
        authProfile.state().cookies().forEach((key, value) -> values.put("auth.cookie." + key, value));
        return Map.copyOf(values);
    }

    /** Returns the state update time displayed by the Session Handler UI. */
    public long displayStateUpdatedAt(SessionProfile profile) {
        if (profile != null && profile.usesLinkedAuthProfile() && accessRepository != null) {
            return accessRepository.authProfile(profile.linkedAuthProfileId())
                    .map(authProfile -> authProfile.state().lastUpdatedAtEpochMillis())
                    .orElse(0L);
        }
        return profile == null ? 0L : profile.state().lastUpdatedAtEpochMillis();
    }

    /**
     * Renders {@code {{session.key}}} placeholders from one profile state.
     *
     * @param template source template
     * @param state session state
     * @return rendered value, or empty when a referenced session key is missing
     */
    public Optional<String> renderSessionTemplate(String template, SessionState state) {
        return renderProfileTemplate(template, state, false);
    }

    private Optional<String> renderProfileTemplate(String template, SessionState state, boolean allowAuthNamespace) {
        Objects.requireNonNull(state, "state");
        String source = template == null ? "" : template;
        Matcher matcher = SESSION_OR_AUTH_PLACEHOLDER.matcher(source);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            if ("auth".equals(matcher.group(1)) && !allowAuthNamespace) {
                return Optional.empty();
            }
            String key = matcher.group(2);
            String value = state.values().get(key);
            if (value == null) {
                return Optional.empty();
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return Optional.of(rendered.toString());
    }

    private static void addTemplate(Map<String, String> templates, String namespace, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String template = "{{" + namespace + "." + key.strip() + "}}";
        templates.put(template, template);
    }

    private enum AuthRunMode { ON_DEMAND, FORCED, SCHEDULED, MAINTENANCE }

    private SessionProfile runAuthFlowLocked(String profileId, AuthRunMode mode) {
        return runAuthFlowLockedWithResult(profileId, mode).profile().orElseGet(SessionProfile::general);
    }

    private SessionAuthenticationResult runAuthFlowLockedWithResult(String profileId, AuthRunMode mode) {
        ReentrantLock lock = lockFor(profileId);
        lock.lock();
        try {
            Optional<SessionProfile> current = repository.profile(profileId);
            if (current.isEmpty()) {
                return SessionAuthenticationResult.failed(profileId,
                        "Session Profile not found: " + profileId, Optional.empty(), List.of());
            }
            SessionProfile storedProfile = current.orElseThrow();
            // Another binding for the same actor may already have maintained its state.
            SessionProfile profile = mode == AuthRunMode.SCHEDULED && linkedKeepAliveEnabled(storedProfile)
                    ? synchronizeLinkedStateMarker(storedProfile, false) : storedProfile;
            if (!hasAuthentication(profile)) {
                return SessionAuthenticationResult.skipped(profile,
                        "No linked Auth Profile or standalone auth flow is configured.");
            }
            long now = clock.millis();
            if (mode == AuthRunMode.SCHEDULED) {
                long last = profile.state().lastAuthenticatedAtEpochMillis();
                long intervalMillis = TimeUnit.SECONDS.toMillis(profile.scheduler().intervalSeconds());
                if (!profile.enabled() || !profile.scheduler().enabled()
                        || (last > 0L && now - last < intervalMillis && !profile.state().missingOrExpired(now))) {
                    return SessionAuthenticationResult.skipped(profile,
                            "Scheduled authentication is disabled or is not due yet.");
                }
            }
            if (mode == AuthRunMode.ON_DEMAND && !profile.state().missingOrExpired(now)) {
                if (!profile.usesLinkedAuthProfile()) {
                    return SessionAuthenticationResult.skipped(profile, "Session state is still valid.");
                }
            }
            if (profile.usesLinkedAuthProfile()) {
                return runLinkedAuthWithResult(profile, mode);
            }
            Optional<FlowDefinition> flow = flowsSupplier.get().stream()
                    .filter(FlowDefinition::enabled)
                    .filter(candidate -> candidate.id().equals(profile.authConfig().mainAuthFlowId()))
                    .findFirst();
            if (flow.isEmpty()) {
                return SessionAuthenticationResult.failed(profile.id(),
                        "Standalone auth flow was not found or is disabled: "
                                + profile.authConfig().mainAuthFlowId(),
                        Optional.of(profile), List.of());
            }
            // Start a new authentication epoch before network I/O. Responses from requests that
            // were already in flight belong to the previous epoch and must not overwrite state
            // produced by this refresh, even when the refresh later fails.
            advanceAuthenticationGeneration(profile.id());
            SessionGuard.enter();
            Optional<Map<String, String>> updates;
            try {
                updates = runAuthFlow(flow.orElseThrow(), profile);
            } finally {
                SessionGuard.exit();
            }
            if (updates.isEmpty()) {
                return SessionAuthenticationResult.failed(profile.id(),
                        "Authentication flow failed or completed without an executable step."
                                + " Check the flow's last run result.",
                        Optional.of(profile), List.of());
            }
            Optional<SessionProfile> saved = repository.updateState(profile.id(),
                    state -> state.withAuthenticatedValues(
                            updates.orElseThrow(), clock.millis(), profile.authConfig().ttlSeconds()));
            saved.ifPresent(updated -> notifyProfileChanged(
                    new SessionProfileChangeEvent(updated.id(), SessionProfileChangeType.STATE_UPDATED)));
            if (saved.isEmpty()) {
                return SessionAuthenticationResult.failed(profile.id(),
                        "Authentication completed, but the Session Profile no longer exists.",
                        Optional.empty(), List.of());
            }
            return SessionAuthenticationResult.succeeded(saved.orElseThrow(),
                    "Standalone auth flow completed successfully.", List.of());
        } finally {
            lock.unlock();
        }
    }

    private SessionAuthenticationResult runLinkedAuthWithResult(SessionProfile profile, AuthRunMode mode) {
        if (!linkedAuthAvailable()) {
            return SessionAuthenticationResult.failed(profile.id(),
                    "Shared Authentication runtime is unavailable.", Optional.of(profile), List.of());
        }
        long now = clock.millis();
        boolean usesKeepAlive = mode != AuthRunMode.FORCED && linkedKeepAliveEnabled(profile);
        AuthState beforeState = usesKeepAlive
                ? accessRepository.authProfile(profile.linkedAuthProfileId()).map(AuthProfile::state).orElse(null)
                : null;
        boolean startsAuthentication = !usesKeepAlive && (mode != AuthRunMode.ON_DEMAND
                || profile.state().missingOrExpired(now));
        if (startsAuthentication) {
            // Invalidate extraction from requests using the previous auth state before the shared
            // AuthManager starts its refresh/login sequence.
            advanceAuthenticationGeneration(profile.id());
        }
        SessionGuard.enter();
        AuthEnsureResult result;
        try {
            result = switch (mode) {
                case ON_DEMAND -> authManager.ensureAuth(profile.linkedAuthProfileId(), "", Map.of(), RenderMode.STRICT);
                case SCHEDULED, MAINTENANCE -> authManager.maintainAuth(profile.linkedAuthProfileId(), "", Map.of(), RenderMode.STRICT);
                case FORCED -> authManager.forceAuth(profile.linkedAuthProfileId(), "", Map.of(), RenderMode.STRICT);
            };
        } finally {
            SessionGuard.exit();
        }
        if (result.failed()) {
            if (usesKeepAlive) advanceAuthenticationGeneration(profile.id());
            return SessionAuthenticationResult.failed(profile.id(), result.reason(),
                    Optional.of(profile), result.warnings());
        }
        boolean authValuesChanged = beforeState != null && result.updatedProfile().map(AuthProfile::state)
                .map(after -> !beforeState.variables().equals(after.variables())
                        || !beforeState.headers().equals(after.headers())
                        || !beforeState.cookies().equals(after.cookies())).orElse(false);
        if (!startsAuthentication && (result.status() == AuthEnsureStatus.LOGGED_IN
                || result.status() == AuthEnsureStatus.REFRESHED || authValuesChanged)) {
            // The shared auth state can expire independently of the Session Handler marker.
            advanceAuthenticationGeneration(profile.id());
        }
        if (result.status() == AuthEnsureStatus.DEFERRED) {
            return new SessionAuthenticationResult(profile.id(), SessionAuthenticationResult.Outcome.SKIPPED,
                    result.reason(), Optional.of(profile), result.warnings());
        }
        SessionProfile synchronizedProfile = synchronizeLinkedStateMarker(profile, true);
        notifyProfileChanged(new SessionProfileChangeEvent(
                synchronizedProfile.id(), SessionProfileChangeType.STATE_UPDATED));
        if (result.status() == AuthEnsureStatus.MANUAL || result.status() == AuthEnsureStatus.SKIPPED) {
            return new SessionAuthenticationResult(profile.id(), SessionAuthenticationResult.Outcome.SKIPPED,
                    result.reason(), Optional.of(synchronizedProfile), result.warnings());
        }
        return SessionAuthenticationResult.succeeded(synchronizedProfile, result.reason(), result.warnings());
    }

    private boolean linkedKeepAliveEnabled(SessionProfile profile) {
        return profile.usesLinkedAuthProfile() && accessRepository != null
                && accessRepository.authProfile(profile.linkedAuthProfileId())
                .flatMap(actor -> accessRepository.authProvider(actor.providerId()))
                .map(provider -> provider.keepAlive().enabled()).orElse(false);
    }

    private ReentrantLock lockFor(String profileId) {
        String normalizedId = Objects.requireNonNull(profileId, "profileId").strip();
        if (normalizedId.isEmpty()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return locks.computeIfAbsent(normalizedId, ignored -> new ReentrantLock());
    }

    private long authenticationGeneration(String profileId) {
        AtomicLong generation = authenticationGenerations.get(profileId);
        return generation == null ? 0L : generation.get();
    }

    private void advanceAuthenticationGeneration(String profileId) {
        authenticationGenerations.computeIfAbsent(profileId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private SessionProfile synchronizeLinkedStateMarker(SessionProfile profile, boolean markAuthenticatedNow) {
        if (accessRepository == null) {
            return profile;
        }
        AuthProfile authProfile = accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null);
        if (authProfile == null) {
            return profile;
        }
        AuthState authState = authProfile.state();
        long updatedAt = authState.lastUpdatedAtEpochMillis();
        if (updatedAt == 0L && markAuthenticatedNow) {
            updatedAt = clock.millis();
        }
        long lastAuthenticated = updatedAt > 0L ? updatedAt : profile.state().lastAuthenticatedAtEpochMillis();
        SessionState marker = new SessionState(
                Map.of(), lastAuthenticated, authState.expiresAtEpochMillis(), updatedAt);
        if (marker.equals(profile.state())) {
            return profile;
        }
        return repository.updateState(profile.id(), ignored -> marker).orElse(profile);
    }

    private static SessionState linkedState(AuthProfile authProfile) {
        AuthState state = authProfile.state();
        LinkedHashMap<String, String> values = new LinkedHashMap<>(state.variables());
        state.headers().forEach((key, value) -> values.put("header." + key, value));
        state.cookies().forEach((key, value) -> values.put("cookie." + key, value));
        return new SessionState(values, state.lastUpdatedAtEpochMillis(),
                state.expiresAtEpochMillis(), state.lastUpdatedAtEpochMillis());
    }

    private boolean updateLinkedAuthState(SessionProfile profile, Map<String, String> updates) {
        if (accessRepository == null) {
            return false;
        }
        AuthProfile authProfile = accessRepository.authProfile(profile.linkedAuthProfileId()).orElse(null);
        if (authProfile == null) {
            return false;
        }
        LinkedHashMap<String, String> variables = new LinkedHashMap<>(authProfile.state().variables());
        variables.putAll(updates);
        AuthState old = authProfile.state();
        AuthState updated = new AuthState(
                variables, old.headers(), old.cookies(), old.expiresAtEpochMillis(), clock.millis());
        accessRepository.saveAuthProfile(new AuthProfile(
                authProfile.id(), authProfile.name(), authProfile.role(), authProfile.providerId(),
                authProfile.credentials(), updated, authProfile.enabled()));
        return true;
    }

    private void clearLinkedAuthState(SessionProfile profile) {
        if (accessRepository == null) {
            return;
        }
        accessRepository.authProfile(profile.linkedAuthProfileId()).ifPresent(authProfile ->
                accessRepository.saveAuthProfile(new AuthProfile(
                        authProfile.id(), authProfile.name(), authProfile.role(), authProfile.providerId(),
                        authProfile.credentials(), AuthState.empty(), authProfile.enabled())));
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

    private Optional<Map<String, String>> runAuthFlow(FlowDefinition flow, SessionProfile profile) {
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
        if (InteractionAbortedException.isAborted(result.failureMessage())) {
            throw new InteractionAbortedException("Session authentication interaction aborted");
        }
        if (!result.success() || result.stepRecords().isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        result.finalContext().ifPresent(finalContext ->
                finalContext.variables().forEach((key, value) -> collectSessionVariable(updates, key, value)));
        result.stepRecords().forEach(record -> {
            record.extractedVariables().forEach((key, value) -> collectSessionVariable(updates, key, value));
            applyAuthFlowExtractionRules(profile, record).forEach(updates::put);
        });
        // A successful keep-alive request may intentionally produce no session variables.
        return Optional.of(updates);
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
