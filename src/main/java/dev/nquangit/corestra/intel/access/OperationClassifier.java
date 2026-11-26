package dev.nquangit.corestra.intel.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Infers {@link OperationMetadata} (read-only, state-changing, idempotent, consumable) from a request.
 *
 * <p>The result drives safety decisions elsewhere (plan previews, race candidates, teardown hints),
 * so the classifier errs on the side of "state-changing" whenever the evidence is mixed.</p>
 */
public final class OperationClassifier {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> ACTION_WORDS = Set.of(
            "delete", "remove", "destroy", "create", "add", "update", "edit", "save", "approve", "reject",
            "cancel", "logout", "signout", "transfer", "reset", "activate", "deactivate", "enable", "disable",
            "submit", "confirm", "assign", "revoke", "grant", "invite", "upload", "import", "publish");
    /** Verbs that consume something even when sent with GET. */
    private static final Set<String> CONSUMABLE_VERBS = Set.of(
            "redeem", "claim", "vote", "transfer", "withdraw", "pay", "checkout", "purchase", "refund",
            "verify", "apply", "redemption");
    /** Nouns that indicate a limited resource, but only for state-changing methods ({@code GET /payments} is a read). */
    private static final Set<String> CONSUMABLE_NOUNS = Set.of(
            "coupon", "voucher", "promo", "promotion", "gift", "giftcard", "like", "withdrawal", "payment",
            "reward", "otp", "verification", "invite", "invitation", "bonus", "points");

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Classifies a request.
     *
     * @param request request template
     * @return suggested metadata and the reasons behind it
     */
    public OperationSuggestion classify(StoredRequestTemplate request) {
        Objects.requireNonNull(request, "request");
        String method = Objects.requireNonNullElse(request.method(), "").strip().toUpperCase(Locale.ROOT);
        List<String> pathWords = pathWords(request.pathAndQuery());
        List<String> reasons = new ArrayList<>();

        Boolean graphQlMutation = graphQlMutation(request);
        if (graphQlMutation != null) {
            if (!graphQlMutation) {
                reasons.add("GraphQL query (no mutation)");
                return new OperationSuggestion(
                        new OperationMetadata(OperationType.READ_ONLY, false, false, "GraphQL query"), reasons);
            }
            reasons.add("GraphQL mutation");
        }

        boolean safeMethod = SAFE_METHODS.contains(method) && graphQlMutation == null;
        String consumableWord = pathWords.stream()
                .filter(word -> CONSUMABLE_VERBS.contains(word) || (!safeMethod && CONSUMABLE_NOUNS.contains(word)))
                .findFirst().orElse(null);
        String actionWord = pathWords.stream().filter(ACTION_WORDS::contains).findFirst().orElse(null);

        if (safeMethod && actionWord == null && consumableWord == null) {
            reasons.add(method + " without action words in the path");
            return new OperationSuggestion(
                    new OperationMetadata(OperationType.READ_ONLY, false, false, "Inferred: " + method), reasons);
        }
        if (safeMethod) {
            reasons.add(method + " but the path contains '" + Objects.requireNonNullElse(actionWord, consumableWord)
                    + "', so it may change state");
        }
        if (consumableWord != null) {
            reasons.add("Path contains one-time/limited action word '" + consumableWord + "'");
            return new OperationSuggestion(
                    new OperationMetadata(OperationType.CONSUMABLE, true, true,
                            "Inferred: consumable action (" + consumableWord + ")"), reasons);
        }
        if (method.equals("PUT")) {
            reasons.add("PUT replaces a resource (idempotent, state-changing)");
            return new OperationSuggestion(
                    new OperationMetadata(OperationType.IDEMPOTENT, true, false, "Inferred: PUT"), reasons);
        }
        if (!safeMethod) {
            reasons.add(method.isEmpty() ? "Unknown method" : method + " changes server state");
        }
        return new OperationSuggestion(
                new OperationMetadata(OperationType.STATE_CHANGING, true, false,
                        "Inferred: " + (method.isEmpty() ? "state-changing" : method)), reasons);
    }

    private Boolean graphQlMutation(StoredRequestTemplate request) {
        String path = Objects.requireNonNullElse(request.pathAndQuery(), "").toLowerCase(Locale.ROOT);
        String body = Objects.requireNonNullElse(request.body(), "").strip();
        if (!path.contains("graphql") && !body.startsWith("{\"query\"") && !body.startsWith("{\"operationName\"")) {
            return null;
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode query = root == null ? null : root.get("query");
            if (query == null || !query.isTextual()) {
                return path.contains("graphql") ? Boolean.TRUE : null;
            }
            return query.asText().stripLeading().toLowerCase(Locale.ROOT).startsWith("mutation");
        } catch (Exception exception) {
            return path.contains("graphql") ? Boolean.TRUE : null;
        }
    }

    private static List<String> pathWords(String pathAndQuery) {
        String value = Objects.requireNonNullElse(pathAndQuery, "");
        int query = value.indexOf('?');
        String path = query < 0 ? value : value.substring(0, query);
        return ObjectIdentifierDetector.words(path);
    }

    /**
     * Suggested operation metadata and why.
     *
     * @param metadata suggested metadata
     * @param reasons human-readable reasons
     */
    public record OperationSuggestion(OperationMetadata metadata, List<String> reasons) {
        /** Freezes the reasons. */
        public OperationSuggestion {
            Objects.requireNonNull(metadata, "metadata");
            reasons = List.copyOf(reasons);
        }
    }
}
