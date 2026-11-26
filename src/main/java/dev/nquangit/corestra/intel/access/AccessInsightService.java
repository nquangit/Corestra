package dev.nquangit.corestra.intel.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.matrix.MatrixGenerationStrategy;
import dev.nquangit.corestra.access.matrix.RoleClassifier;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.intel.access.AccessInsight.RiskLevel;
import dev.nquangit.corestra.intel.access.AccessInsight.StrategyRecommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Scores access request templates for authorization risk and recommends how to test them.
 *
 * <p>Everything is derived offline and deterministically from the template, an optional captured
 * response and the current workspace, so every point of the score has a stated reason.</p>
 */
public final class AccessInsightService {
    private static final Set<String> PRIVILEGED_WORDS = Set.of(
            "admin", "administrator", "role", "roles", "permission", "permissions", "privilege", "privileges",
            "config", "configuration", "settings", "setting", "impersonate", "internal", "debug", "manage",
            "management", "superuser", "staff", "acl", "policy", "policies", "audit", "users");
    private static final Set<String> APPROVAL_WORDS = Set.of(
            "approve", "approval", "approvals", "reject", "review", "authorize", "authorise", "sign", "signoff",
            "verify", "release", "confirm");
    private static final Set<String> DATA_EXPORT_WORDS = Set.of(
            "export", "download", "report", "reports", "backup", "dump", "statement", "statements", "invoice",
            "invoices", "billing");
    private static final Set<String> TENANT_WORDS = Set.of(
            "tenant", "tenants", "org", "orgs", "organization", "organizations", "organisation", "company",
            "companies", "workspace", "workspaces", "account", "accounts");
    private static final Set<String> BRANCH_WORDS = Set.of("branch", "branches", "store", "stores", "site", "sites");
    private static final Set<String> PUBLIC_WORDS = Set.of(
            "login", "signin", "signup", "register", "public", "static", "assets", "health", "healthz", "ping",
            "status", "favicon", "robots");
    private static final Set<String> SENSITIVE_FIELD_WORDS = Set.of(
            "email", "phone", "mobile", "ssn", "dob", "birthdate", "birthday", "address", "salary", "iban",
            "passport", "nationalid", "taxid", "cardnumber", "pan", "cvv", "password", "secret", "token",
            "apikey", "accountnumber", "income", "diagnosis");

    private final ObjectIdentifierDetector identifierDetector = new ObjectIdentifierDetector();
    private final OperationClassifier operationClassifier = new OperationClassifier();
    private final RoleClassifier roleClassifier = new RoleClassifier();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Analyzes every template in the workspace, highest risk first.
     *
     * @param workspace access workspace snapshot
     * @param sampleResponse captured response lookup per template (may return empty)
     * @return insights ordered by descending score, then name
     */
    public List<AccessInsight> analyzeAll(
            AccessWorkspace workspace,
            Function<AccessRequestTemplate, Optional<HttpMessageSnapshot>> sampleResponse) {
        Objects.requireNonNull(workspace, "workspace");
        Function<AccessRequestTemplate, Optional<HttpMessageSnapshot>> lookup =
                sampleResponse == null ? template -> Optional.empty() : sampleResponse;
        return workspace.templates().values().stream()
                .map(template -> analyze(template, lookup.apply(template).orElse(null), workspace))
                .sorted(Comparator.comparingInt(AccessInsight::score).reversed()
                        .thenComparing(AccessInsight::templateName))
                .toList();
    }

    /**
     * Analyzes one template.
     *
     * @param template template
     * @param sampleResponse captured response, or null
     * @param workspace workspace used for readiness checks
     * @return insight
     */
    public AccessInsight analyze(AccessRequestTemplate template, HttpMessageSnapshot sampleResponse,
                                 AccessWorkspace workspace) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(workspace, "workspace");
        List<IdentifierOccurrence> identifiers = identifierDetector.detect(template.baseRequest());
        OperationClassifier.OperationSuggestion operation = operationClassifier.classify(template.baseRequest());
        List<String> pathWords = ObjectIdentifierDetector.words(path(template.baseRequest().pathAndQuery()));
        List<String> sensitiveFields = sampleResponse == null ? List.of() : sensitiveFieldPaths(sampleResponse);

        int score = 0;
        List<String> reasons = new ArrayList<>();
        boolean pathIdentifier = identifiers.stream().anyMatch(id -> id.location() == IdentifierOccurrence.Location.PATH);
        if (pathIdentifier) {
            score += 35;
            reasons.add("+35 object identifier in the path (classic IDOR/BOLA shape)");
        } else if (!identifiers.isEmpty()) {
            score += 25;
            reasons.add("+25 object identifier in query/body");
        }
        if (identifiers.size() > 1) {
            score += 5;
            reasons.add("+5 several identifiers (" + identifiers.size() + "); mixing owners is possible");
        }
        OperationMetadata metadata = operation.metadata();
        if (metadata.stateChanging()) {
            score += 15;
            reasons.add("+15 state-changing operation (" + metadata.operationType() + ")");
        }
        if (metadata.consumable()) {
            score += 10;
            reasons.add("+10 consumable/limited action; also a race-condition candidate");
        }
        String privileged = firstMatch(pathWords, PRIVILEGED_WORDS);
        if (privileged != null) {
            score += 15;
            reasons.add("+15 privileged function keyword '" + privileged + "'");
        }
        String export = firstMatch(pathWords, DATA_EXPORT_WORDS);
        if (export != null) {
            score += 10;
            reasons.add("+10 bulk data keyword '" + export + "'");
        }
        if (!sensitiveFields.isEmpty()) {
            score += 10;
            reasons.add("+10 captured response contains sensitive fields (" + sensitiveFields.size() + ")");
        }
        String publicWord = firstMatch(pathWords, PUBLIC_WORDS);
        if (publicWord != null && identifiers.isEmpty()) {
            score -= 20;
            reasons.add("-20 looks public ('" + publicWord + "')");
        }
        score = Math.max(0, Math.min(100, score));
        RiskLevel level = score >= 60 ? RiskLevel.HIGH : score >= 30 ? RiskLevel.MEDIUM : RiskLevel.LOW;

        WorkspaceFacts facts = WorkspaceFacts.of(workspace, roleClassifier);
        boolean hasDataPlaceholder = template.placeholders().stream()
                .anyMatch(ref -> ref.scope() == VariableScope.DATA)
                || identifiers.stream().anyMatch(id -> id.parameterized() && id.value().contains("data."));
        List<StrategyRecommendation> strategies = strategies(identifiers, pathWords, publicWord, facts,
                hasDataPlaceholder, template);
        List<String> readiness = readiness(template, identifiers, hasDataPlaceholder, facts);
        boolean differs = !sameOperation(template.operationMetadata(), metadata);
        return new AccessInsight(template.id(), template.name(), score, level, reasons, identifiers, operation,
                differs, strategies, sensitiveFields, readiness);
    }

    private List<StrategyRecommendation> strategies(
            List<IdentifierOccurrence> identifiers,
            List<String> pathWords,
            String publicWord,
            WorkspaceFacts facts,
            boolean hasDataPlaceholder,
            AccessRequestTemplate template) {
        List<StrategyRecommendation> strategies = new ArrayList<>();
        if (!identifiers.isEmpty()) {
            List<String> missing = new ArrayList<>();
            if (!hasDataPlaceholder) {
                missing.add("Parameterize the identifier(s) as {{data.…}} placeholders");
            }
            if (!facts.hasOwnedDataProfile()) {
                missing.add("A Data Profile with an enabled owner Auth Profile");
            }
            if (facts.enabledAuthProfiles() < 2) {
                missing.add("At least two enabled Auth Profiles (owner and another actor)");
            }
            strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.OWNER_VS_OTHERS,
                    "Object identifier(s) present: check that other actors cannot reach the owner's object", missing));
            if (identifiers.size() > 1) {
                strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.OBJECT_OWNERSHIP_VARIANTS,
                        "Several identifiers: vary ownership of each object independently", missing));
            }
        }
        String privileged = firstMatch(pathWords, PRIVILEGED_WORDS);
        if (privileged != null) {
            List<String> missing = new ArrayList<>();
            if (!facts.hasNonAdminActor()) {
                missing.add("An enabled non-admin, non-anonymous Auth Profile");
            }
            strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.ROLE_ESCALATION,
                    "Privileged function ('" + privileged + "'): a lower role must be denied", missing));
        }
        String approval = firstMatch(pathWords, APPROVAL_WORDS);
        if (approval != null) {
            List<String> missing = new ArrayList<>();
            if (facts.distinctRoles() < 2) {
                missing.add("Auth Profiles with distinct maker and checker roles");
            }
            if (!facts.hasOwnedDataProfile()) {
                missing.add("A Data Profile owned by the maker");
            }
            strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.MAKER_CHECKER,
                    "Approval step ('" + approval + "'): the maker must not approve their own item", missing));
        }
        String tenant = firstMatch(pathWords, TENANT_WORDS);
        boolean tenantIdentifier = identifiers.stream().anyMatch(id -> containsAny(id.name(), TENANT_WORDS));
        if (tenant != null || tenantIdentifier) {
            strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.CROSS_TENANT,
                    "Tenant-scoped resource: actors from another tenant must be denied",
                    facts.hasTenantKey() ? List.of() : List.of("Data Profiles with a tenantId value per owner")));
        }
        String branch = firstMatch(pathWords, BRANCH_WORDS);
        if (branch != null) {
            strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.CROSS_BRANCH,
                    "Branch-scoped resource ('" + branch + "')",
                    facts.hasBranchKey() ? List.of() : List.of("Data Profiles with a branchId value per owner")));
        }
        if (publicWord == null) {
            strategies.add(new StrategyRecommendation(MatrixGenerationStrategy.ANONYMOUS_ACCESS,
                    "Every non-public endpoint should reject unauthenticated requests",
                    facts.hasAnonymousActor() ? List.of() : List.of("An Auth Profile named/roled anonymous or guest")));
        }
        if (!template.enabled()) {
            strategies.replaceAll(recommendation -> {
                List<String> missing = new ArrayList<>(recommendation.missingPrerequisites());
                missing.addFirst("Enable the template");
                return new StrategyRecommendation(recommendation.strategy(), recommendation.reason(), missing);
            });
        }
        return strategies;
    }

    private static List<String> readiness(AccessRequestTemplate template, List<IdentifierOccurrence> identifiers,
                                          boolean hasDataPlaceholder, WorkspaceFacts facts) {
        List<String> issues = new ArrayList<>();
        if (!template.enabled()) {
            issues.add("Template is disabled; Generate Matrix skips it");
        }
        long literal = identifiers.stream().filter(id -> !id.parameterized()).count();
        if (literal > 0) {
            issues.add(literal + " literal identifier(s) still hard-coded; every actor would request the same object");
        }
        for (ScopedVariableRef ref : template.placeholders()) {
            if (ref.scope() == VariableScope.DATA && !facts.dataKeys().contains(ref.key())) {
                issues.add("No enabled Data Profile defines data." + ref.key());
            }
        }
        if (!hasDataPlaceholder && identifiers.isEmpty() && facts.enabledAuthProfiles() < 2) {
            issues.add("Fewer than two enabled Auth Profiles");
        }
        return issues;
    }

    private List<String> sensitiveFieldPaths(HttpMessageSnapshot response) {
        String body = response.body();
        String trimmed = body == null ? "" : body.stripLeading();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("[")) || body.length() > 1_048_576) {
            return List.of();
        }
        try {
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            collectSensitive(json.readTree(body), "", paths, 0);
            return List.copyOf(paths);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static void collectSensitive(JsonNode node, String path, Set<String> paths, int depth) {
        if (node == null || depth > 32 || paths.size() >= 50) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey();
                if (key.contains(".") || key.contains("[")) {
                    continue;
                }
                String childPath = path.isEmpty() ? key : path + "." + key;
                JsonNode value = field.getValue();
                if (value.isValueNode() && !value.isNull() && isSensitiveField(key)) {
                    paths.add(childPath);
                } else {
                    collectSensitive(value, childPath, paths, depth + 1);
                }
            }
        } else if (node.isArray() && !node.isEmpty()) {
            collectSensitive(node.get(0), path.isEmpty() ? "[*]" : path + "[*]", paths, depth + 1);
        }
    }

    static boolean isSensitiveField(String key) {
        String compact = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SENSITIVE_FIELD_WORDS.contains(compact)
                || compact.endsWith("email") || compact.endsWith("phone") || compact.endsWith("password")) {
            return true;
        }
        // Judge by the last word so "accessToken" or "homeAddress" match but "emailVerified" does not.
        List<String> words = ObjectIdentifierDetector.words(key);
        return !words.isEmpty() && SENSITIVE_FIELD_WORDS.contains(words.getLast());
    }

    private static boolean sameOperation(OperationMetadata current, OperationMetadata suggested) {
        return current != null && current.operationType() == suggested.operationType()
                && current.stateChanging() == suggested.stateChanging()
                && current.consumable() == suggested.consumable();
    }

    private static String firstMatch(List<String> words, Set<String> vocabulary) {
        return words.stream().filter(vocabulary::contains).findFirst().orElse(null);
    }

    private static boolean containsAny(String name, Set<String> vocabulary) {
        return ObjectIdentifierDetector.words(name).stream().anyMatch(vocabulary::contains);
    }

    private static String path(String pathAndQuery) {
        String value = Objects.requireNonNullElse(pathAndQuery, "");
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query);
    }

    /** Facts about the workspace used for readiness checks. */
    private record WorkspaceFacts(
            int enabledAuthProfiles,
            boolean hasAnonymousActor,
            boolean hasNonAdminActor,
            int distinctRoles,
            boolean hasOwnedDataProfile,
            boolean hasTenantKey,
            boolean hasBranchKey,
            Set<String> dataKeys) {

        static WorkspaceFacts of(AccessWorkspace workspace, RoleClassifier roles) {
            List<AuthProfile> enabled = workspace.authProfiles().values().stream().filter(AuthProfile::enabled).toList();
            Set<String> enabledIds = new LinkedHashSet<>();
            enabled.forEach(profile -> enabledIds.add(profile.id()));
            Set<String> distinctRoles = new LinkedHashSet<>();
            enabled.forEach(profile -> {
                if (!profile.role().isBlank()) {
                    distinctRoles.add(profile.role().toLowerCase(Locale.ROOT));
                }
            });
            Set<String> dataKeys = new LinkedHashSet<>();
            boolean owned = false;
            boolean tenant = false;
            boolean branch = false;
            for (DataProfile data : workspace.dataProfiles().values()) {
                if (!data.enabled()) {
                    continue;
                }
                dataKeys.addAll(data.values().keySet());
                owned |= enabledIds.contains(data.ownerAuthProfileId());
                tenant |= data.values().keySet().stream().anyMatch(key -> key.equalsIgnoreCase("tenantId"));
                branch |= data.values().keySet().stream().anyMatch(key -> key.equalsIgnoreCase("branchId"));
            }
            return new WorkspaceFacts(
                    enabled.size(),
                    enabled.stream().anyMatch(roles::isAnonymous),
                    enabled.stream().anyMatch(profile -> !roles.isAdmin(profile) && !roles.isAnonymous(profile)),
                    distinctRoles.size(),
                    owned,
                    tenant,
                    branch,
                    dataKeys);
        }
    }
}
