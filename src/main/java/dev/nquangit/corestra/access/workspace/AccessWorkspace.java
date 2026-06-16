package dev.nquangit.corestra.access.workspace;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of all access-testing workspace objects.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessWorkspace {
    private final Map<String, AuthProvider> authProviders;
    private final Map<String, AuthProfile> authProfiles;
    private final Map<String, DataProfile> dataProfiles;
    private final Map<String, AccessRequestTemplate> templates;
    private final Map<String, AccessFlowTarget> flowTargets;
    private final Map<String, AccessTestCase> testCases;
    private final Map<String, AccessTestResult> results;
    private final Map<String, ScenarioInstance> scenarios;
    private final Map<String, EvaluationProfile> evaluationProfiles;

    /**
     * Creates an immutable access workspace snapshot.
     *
     * @param authProfiles auth profiles keyed by profile id
     * @param dataProfiles data profiles keyed by profile id
     * @param templates access request templates keyed by template id
     * @param testCases access test cases keyed by test case id
     * @param results access test results keyed by result id
     */
    public AccessWorkspace(
            @JsonProperty("authProfiles") Map<String, AuthProfile> authProfiles,
            @JsonProperty("dataProfiles") Map<String, DataProfile> dataProfiles,
            @JsonProperty("templates") Map<String, AccessRequestTemplate> templates,
            @JsonProperty("testCases") Map<String, AccessTestCase> testCases,
            @JsonProperty("results") Map<String, AccessTestResult> results) {
        this(Map.of(), authProfiles, dataProfiles, templates, Map.of(), testCases, results, Map.of(), Map.of());
    }

    /**
     * Creates an immutable access workspace snapshot.
     *
     * @param authProviders auth providers keyed by provider id
     * @param authProfiles auth profiles keyed by profile id
     * @param dataProfiles data profiles keyed by profile id
     * @param templates access request templates keyed by template id
     * @param testCases access test cases keyed by test case id
     * @param results access test results keyed by result id
     */
    public AccessWorkspace(
            Map<String, AuthProvider> authProviders,
            Map<String, AuthProfile> authProfiles,
            Map<String, DataProfile> dataProfiles,
            Map<String, AccessRequestTemplate> templates,
            Map<String, AccessTestCase> testCases,
            Map<String, AccessTestResult> results) {
        this(authProviders, authProfiles, dataProfiles, templates, Map.of(), testCases, results, Map.of(), Map.of());
    }

    /**
     * Creates an immutable access workspace snapshot.
     *
     * @param authProviders auth providers keyed by provider id
     * @param authProfiles auth profiles keyed by profile id
     * @param dataProfiles data profiles keyed by profile id
     * @param templates access request templates keyed by template id
     * @param testCases access test cases keyed by test case id
     * @param results access test results keyed by result id
     * @param scenarios scenario instances keyed by scenario id
     * @param evaluationProfiles evaluation profiles keyed by profile id
     */
    public AccessWorkspace(
            Map<String, AuthProvider> authProviders,
            Map<String, AuthProfile> authProfiles,
            Map<String, DataProfile> dataProfiles,
            Map<String, AccessRequestTemplate> templates,
            Map<String, AccessTestCase> testCases,
            Map<String, AccessTestResult> results,
            Map<String, ScenarioInstance> scenarios,
            Map<String, EvaluationProfile> evaluationProfiles) {
        this(authProviders, authProfiles, dataProfiles, templates, Map.of(), testCases, results,
                scenarios, evaluationProfiles);
    }

    /**
     * Creates an immutable access workspace snapshot.
     *
     * @param authProviders auth providers keyed by provider id
     * @param authProfiles auth profiles keyed by profile id
     * @param dataProfiles data profiles keyed by profile id
     * @param templates access request templates keyed by template id
     * @param flowTargets access flow targets keyed by target id
     * @param testCases access test cases keyed by test case id
     * @param results access test results keyed by result id
     * @param scenarios scenario instances keyed by scenario id
     * @param evaluationProfiles evaluation profiles keyed by profile id
     */
    @JsonCreator
    public AccessWorkspace(
            @JsonProperty("authProviders") Map<String, AuthProvider> authProviders,
            @JsonProperty("authProfiles") Map<String, AuthProfile> authProfiles,
            @JsonProperty("dataProfiles") Map<String, DataProfile> dataProfiles,
            @JsonProperty("templates") Map<String, AccessRequestTemplate> templates,
            @JsonProperty("flowTargets") Map<String, AccessFlowTarget> flowTargets,
            @JsonProperty("testCases") Map<String, AccessTestCase> testCases,
            @JsonProperty("results") Map<String, AccessTestResult> results,
            @JsonProperty("scenarios") Map<String, ScenarioInstance> scenarios,
            @JsonProperty("evaluationProfiles") Map<String, EvaluationProfile> evaluationProfiles) {
        this.authProviders = copyMap(authProviders, "authProviders");
        this.authProfiles = copyMap(authProfiles, "authProfiles");
        this.dataProfiles = copyMap(dataProfiles, "dataProfiles");
        this.templates = copyMap(templates, "templates");
        this.flowTargets = copyMap(flowTargets, "flowTargets");
        this.testCases = copyMap(testCases, "testCases");
        this.results = copyMap(results, "results");
        this.scenarios = copyMap(scenarios, "scenarios");
        this.evaluationProfiles = copyMap(evaluationProfiles, "evaluationProfiles");
    }

    /**
     * Returns an empty workspace snapshot.
     *
     * @return an empty access workspace
     */
    public static AccessWorkspace empty() {
        return builder().build();
    }

    /**
     * Creates a workspace builder.
     *
     * @return a workspace builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder initialized with this workspace's contents.
     *
     * @return a populated workspace builder
     */
    public Builder toBuilder() {
        return builder()
                .authProviders(authProviders)
                .authProfiles(authProfiles)
                .dataProfiles(dataProfiles)
                .templates(templates)
                .flowTargets(flowTargets)
                .testCases(testCases)
                .results(results)
                .scenarios(scenarios)
                .evaluationProfiles(evaluationProfiles);
    }

    /**
     * Returns a copy of this workspace with one auth provider saved.
     *
     * @param provider auth provider to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withAuthProvider(AuthProvider provider) {
        return toBuilder().authProvider(provider).build();
    }

    /**
     * Returns a copy of this workspace with one auth profile saved.
     *
     * @param profile auth profile to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withAuthProfile(AuthProfile profile) {
        return toBuilder().authProfile(profile).build();
    }

    /**
     * Returns a copy of this workspace with one data profile saved.
     *
     * @param profile data profile to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withDataProfile(DataProfile profile) {
        return toBuilder().dataProfile(profile).build();
    }

    /**
     * Returns a copy of this workspace with one template saved.
     *
     * @param template access request template to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withTemplate(AccessRequestTemplate template) {
        return toBuilder().template(template).build();
    }

    /**
     * Returns a copy of this workspace with one flow target saved.
     *
     * @param flowTarget access flow target to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withFlowTarget(AccessFlowTarget flowTarget) {
        return toBuilder().flowTarget(flowTarget).build();
    }

    /**
     * Returns a copy of this workspace with one test case saved.
     *
     * @param testCase access test case to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withTestCase(AccessTestCase testCase) {
        return toBuilder().testCase(testCase).build();
    }

    /**
     * Returns a copy of this workspace with one test result saved.
     *
     * @param result access test result to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withResult(AccessTestResult result) {
        return toBuilder().result(result).build();
    }

    /**
     * Returns a copy of this workspace with one scenario saved.
     *
     * @param scenario scenario instance to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withScenario(ScenarioInstance scenario) {
        return toBuilder().scenario(scenario).build();
    }

    /**
     * Returns a copy of this workspace with one evaluation profile saved.
     *
     * @param profile evaluation profile to save
     * @return a new workspace snapshot
     */
    public AccessWorkspace withEvaluationProfile(EvaluationProfile profile) {
        return toBuilder().evaluationProfile(profile).build();
    }

    /**
     * Returns auth providers keyed by provider id.
     *
     * @return immutable auth provider map
     */
    public Map<String, AuthProvider> authProviders() {
        return authProviders;
    }

    /**
     * Returns auth profiles keyed by profile id.
     *
     * @return immutable auth profile map
     */
    public Map<String, AuthProfile> authProfiles() {
        return authProfiles;
    }

    /**
     * Returns data profiles keyed by profile id.
     *
     * @return immutable data profile map
     */
    public Map<String, DataProfile> dataProfiles() {
        return dataProfiles;
    }

    /**
     * Returns access request templates keyed by template id.
     *
     * @return immutable template map
     */
    public Map<String, AccessRequestTemplate> templates() {
        return templates;
    }

    /**
     * Returns access flow targets keyed by target id.
     *
     * @return immutable flow target map
     */
    public Map<String, AccessFlowTarget> flowTargets() {
        return flowTargets;
    }

    /**
     * Returns access test cases keyed by test case id.
     *
     * @return immutable test case map
     */
    public Map<String, AccessTestCase> testCases() {
        return testCases;
    }

    /**
     * Returns access test results keyed by result id.
     *
     * @return immutable result map
     */
    public Map<String, AccessTestResult> results() {
        return results;
    }

    /**
     * Returns scenario instances keyed by scenario id.
     *
     * @return immutable scenario map
     */
    public Map<String, ScenarioInstance> scenarios() {
        return scenarios;
    }

    /**
     * Returns evaluation profiles keyed by profile id.
     *
     * @return immutable evaluation profile map
     */
    public Map<String, EvaluationProfile> evaluationProfiles() {
        return evaluationProfiles;
    }

    /**
     * Compares this workspace to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessWorkspace that)) {
            return false;
        }
        return Objects.equals(authProviders, that.authProviders)
                && Objects.equals(authProfiles, that.authProfiles)
                && Objects.equals(dataProfiles, that.dataProfiles)
                && Objects.equals(templates, that.templates)
                && Objects.equals(flowTargets, that.flowTargets)
                && Objects.equals(testCases, that.testCases)
                && Objects.equals(results, that.results)
                && Objects.equals(scenarios, that.scenarios)
                && Objects.equals(evaluationProfiles, that.evaluationProfiles);
    }

    /**
     * Returns the hash code for this workspace.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(authProviders, authProfiles, dataProfiles, templates, flowTargets, testCases, results, scenarios,
                evaluationProfiles);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AccessWorkspace{" +
                "authProviders=" + authProviders.keySet() +
                ", authProfiles=" + authProfiles.keySet() +
                ", dataProfiles=" + dataProfiles.keySet() +
                ", templates=" + templates.keySet() +
                ", flowTargets=" + flowTargets.keySet() +
                ", testCases=" + testCases.keySet() +
                ", results=" + results.keySet() +
                ", scenarios=" + scenarios.keySet() +
                ", evaluationProfiles=" + evaluationProfiles.keySet() +
                '}';
    }

    private static <T> Map<String, T> copyMap(Map<String, T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, T> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(normalizeRequiredText(key, fieldName + " key"),
                Objects.requireNonNull(value, fieldName + " value")));
        return Collections.unmodifiableMap(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    /**
     * Mutable builder that produces immutable access workspace snapshots.
     */
    public static final class Builder {
        private final LinkedHashMap<String, AuthProvider> authProviders = new LinkedHashMap<>();
        private final LinkedHashMap<String, AuthProfile> authProfiles = new LinkedHashMap<>();
        private final LinkedHashMap<String, DataProfile> dataProfiles = new LinkedHashMap<>();
        private final LinkedHashMap<String, AccessRequestTemplate> templates = new LinkedHashMap<>();
        private final LinkedHashMap<String, AccessFlowTarget> flowTargets = new LinkedHashMap<>();
        private final LinkedHashMap<String, AccessTestCase> testCases = new LinkedHashMap<>();
        private final LinkedHashMap<String, AccessTestResult> results = new LinkedHashMap<>();
        private final LinkedHashMap<String, ScenarioInstance> scenarios = new LinkedHashMap<>();
        private final LinkedHashMap<String, EvaluationProfile> evaluationProfiles = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Replaces auth providers in the builder.
         *
         * @param values auth providers keyed by id
         * @return this builder
         */
        public Builder authProviders(Map<String, AuthProvider> values) {
            authProviders.clear();
            authProviders.putAll(copyMap(values, "authProviders"));
            return this;
        }

        /**
         * Adds or replaces one auth provider.
         *
         * @param provider auth provider to save
         * @return this builder
         */
        public Builder authProvider(AuthProvider provider) {
            Objects.requireNonNull(provider, "provider");
            authProviders.put(provider.id(), provider);
            return this;
        }

        /**
         * Replaces auth profiles in the builder.
         *
         * @param values auth profiles keyed by id
         * @return this builder
         */
        public Builder authProfiles(Map<String, AuthProfile> values) {
            authProfiles.clear();
            authProfiles.putAll(copyMap(values, "authProfiles"));
            return this;
        }

        /**
         * Adds or replaces one auth profile.
         *
         * @param profile auth profile to save
         * @return this builder
         */
        public Builder authProfile(AuthProfile profile) {
            Objects.requireNonNull(profile, "profile");
            authProfiles.put(profile.id(), profile);
            return this;
        }

        /**
         * Replaces data profiles in the builder.
         *
         * @param values data profiles keyed by id
         * @return this builder
         */
        public Builder dataProfiles(Map<String, DataProfile> values) {
            dataProfiles.clear();
            dataProfiles.putAll(copyMap(values, "dataProfiles"));
            return this;
        }

        /**
         * Adds or replaces one data profile.
         *
         * @param profile data profile to save
         * @return this builder
         */
        public Builder dataProfile(DataProfile profile) {
            Objects.requireNonNull(profile, "profile");
            dataProfiles.put(profile.id(), profile);
            return this;
        }

        /**
         * Replaces templates in the builder.
         *
         * @param values templates keyed by id
         * @return this builder
         */
        public Builder templates(Map<String, AccessRequestTemplate> values) {
            templates.clear();
            templates.putAll(copyMap(values, "templates"));
            return this;
        }

        /**
         * Adds or replaces one access request template.
         *
         * @param template access request template to save
         * @return this builder
         */
        public Builder template(AccessRequestTemplate template) {
            Objects.requireNonNull(template, "template");
            templates.put(template.id(), template);
            return this;
        }

        /**
         * Replaces flow targets in the builder.
         *
         * @param values flow targets keyed by id
         * @return this builder
         */
        public Builder flowTargets(Map<String, AccessFlowTarget> values) {
            flowTargets.clear();
            flowTargets.putAll(copyMap(values, "flowTargets"));
            return this;
        }

        /**
         * Adds or replaces one access flow target.
         *
         * @param flowTarget access flow target to save
         * @return this builder
         */
        public Builder flowTarget(AccessFlowTarget flowTarget) {
            Objects.requireNonNull(flowTarget, "flowTarget");
            flowTargets.put(flowTarget.id(), flowTarget);
            return this;
        }

        /**
         * Replaces test cases in the builder.
         *
         * @param values test cases keyed by id
         * @return this builder
         */
        public Builder testCases(Map<String, AccessTestCase> values) {
            testCases.clear();
            testCases.putAll(copyMap(values, "testCases"));
            return this;
        }

        /**
         * Adds or replaces one access test case.
         *
         * @param testCase access test case to save
         * @return this builder
         */
        public Builder testCase(AccessTestCase testCase) {
            Objects.requireNonNull(testCase, "testCase");
            testCases.put(testCase.id(), testCase);
            return this;
        }

        /**
         * Replaces results in the builder.
         *
         * @param values results keyed by id
         * @return this builder
         */
        public Builder results(Map<String, AccessTestResult> values) {
            results.clear();
            results.putAll(copyMap(values, "results"));
            return this;
        }

        /**
         * Adds or replaces one access test result.
         *
         * @param result access test result to save
         * @return this builder
         */
        public Builder result(AccessTestResult result) {
            Objects.requireNonNull(result, "result");
            results.put(result.id(), result);
            return this;
        }

        /**
         * Replaces scenarios in the builder.
         *
         * @param values scenarios keyed by id
         * @return this builder
         */
        public Builder scenarios(Map<String, ScenarioInstance> values) {
            scenarios.clear();
            scenarios.putAll(copyMap(values, "scenarios"));
            return this;
        }

        /**
         * Adds or replaces one scenario instance.
         *
         * @param scenario scenario instance to save
         * @return this builder
         */
        public Builder scenario(ScenarioInstance scenario) {
            Objects.requireNonNull(scenario, "scenario");
            scenarios.put(scenario.id(), scenario);
            return this;
        }

        /**
         * Replaces evaluation profiles in the builder.
         *
         * @param values evaluation profiles keyed by id
         * @return this builder
         */
        public Builder evaluationProfiles(Map<String, EvaluationProfile> values) {
            evaluationProfiles.clear();
            evaluationProfiles.putAll(copyMap(values, "evaluationProfiles"));
            return this;
        }

        /**
         * Adds or replaces one evaluation profile.
         *
         * @param profile evaluation profile to save
         * @return this builder
         */
        public Builder evaluationProfile(EvaluationProfile profile) {
            Objects.requireNonNull(profile, "profile");
            evaluationProfiles.put(profile.id(), profile);
            return this;
        }

        /**
         * Builds an immutable workspace snapshot.
         *
         * @return immutable workspace snapshot
         */
        public AccessWorkspace build() {
            return new AccessWorkspace(authProviders, authProfiles, dataProfiles, templates, flowTargets, testCases, results,
                    scenarios, evaluationProfiles);
        }
    }
}
