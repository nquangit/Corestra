package dev.nquangit.corestra.access.flow;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Produces a stable content hash for flow-target stale-snapshot checks.
 */
public final class FlowTargetHasher {
    /**
     * Hashes the target-relevant flow content.
     *
     * @param flow flow definition
     * @return SHA-256 hex digest
     */
    public String hash(FlowDefinition flow) {
        Objects.requireNonNull(flow, "flow");
        MessageDigest digest = sha256();
        update(digest, flow.id());
        update(digest, flow.name());
        update(digest, flow.description());
        update(digest, Boolean.toString(flow.enabled()));
        for (FlowStep step : flow.steps()) {
            update(digest, step.id());
            update(digest, step.name());
            update(digest, Boolean.toString(step.enabled()));
            StoredRequestTemplate request = step.requestTemplate();
            update(digest, request.method());
            update(digest, request.scheme());
            update(digest, request.host());
            update(digest, Integer.toString(request.port()));
            update(digest, request.pathAndQuery());
            request.headerLines().forEach(header -> update(digest, header));
            update(digest, request.body());
            for (InjectRule rule : step.injectRules()) {
                update(digest, rule.id());
                update(digest, rule.name());
                update(digest, Boolean.toString(rule.enabled()));
                update(digest, rule.target().name());
                update(digest, rule.targetSelector());
                update(digest, rule.valueSourceType().name());
                if (rule.interactiveInput() != null) update(digest, rule.interactiveInput().toString());
                update(digest, rule.valueSourceKey());
                update(digest, rule.staticValue());
                update(digest, rule.writeStrategy().name());
            }
            for (ExtractRule rule : step.extractRules()) {
                update(digest, rule.id());
                update(digest, rule.name());
                update(digest, Boolean.toString(rule.enabled()));
                update(digest, rule.sourceSide().name());
                update(digest, rule.extractorType().name());
                update(digest, rule.sourceSelector());
                update(digest, rule.targetVariableName());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
