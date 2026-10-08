package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpMessage;
import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.List;

/** Reads body bytes at the Montoya boundary without converting through text. */
public final class HttpBodyBytes {
    private HttpBodyBytes() { }

    /** Reads binary data only when an enabled rule needs it on the supplied message side. */
    public static byte[] readForExtraction(HttpMessage message, List<ExtractRule> rules, ExtractRule.SourceSide side) {
        boolean required = rules.stream().anyMatch(rule -> rule.enabled() && rule.sourceSide() == side
                && rule.extractorType() == ExtractRule.ExtractorType.BODY_RAW);
        return required ? read(message) : null;
    }

    /** Returns the body bytes, or {@code null} for adapters that do not expose binary data. */
    public static byte[] read(HttpMessage message) {
        if (message == null) return null;
        try {
            ByteArray body = message.body();
            return body == null ? null : body.getBytes();
        } catch (UnsupportedOperationException exception) {
            // Text-only adapters remain usable; BODY_RAW will report unavailable bytes.
            return null;
        }
    }
}
