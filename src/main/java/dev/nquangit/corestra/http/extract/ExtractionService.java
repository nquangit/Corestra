package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.transform.ValueTransformResult;
import dev.nquangit.corestra.http.transform.ValueTransformService;

import java.util.Objects;

/**
 * Orchestrates value extraction by delegating to the appropriate extractor based on an
 * {@link ExtractRule}.
 *
 * <p>Callers supply two optional {@link HttpMessageSnapshot} instances — one for the request and
 * one for the response. Either may be {@code null} when that side is not available (e.g. a
 * response-side rule will return a failure if no response snapshot is provided).
 */
public final class ExtractionService {

    private final HeaderExtractor headerExtractor;
    private final CookieExtractor cookieExtractor;
    private final JsonBodyExtractor jsonBodyExtractor;
    private final RegexExtractor regexExtractor;
    private final ValueTransformService transformService;

    /**
     * Creates an {@code ExtractionService} with default extractor instances.
     */
    public ExtractionService() {
        this.headerExtractor = new HeaderExtractor();
        this.cookieExtractor = new CookieExtractor();
        this.jsonBodyExtractor = new JsonBodyExtractor();
        this.regexExtractor = new RegexExtractor();
        this.transformService = new ValueTransformService();
    }

    /**
     * Applies the supplied {@link ExtractRule} against the provided message snapshots.
     *
     * <p>The rule's {@link ExtractRule#sourceSide()} determines which snapshot is used.
     * If the required snapshot is {@code null}, a failure is returned immediately.
     *
     * @param rule             the extraction rule to apply; must not be {@code null}
     * @param requestSnapshot  the request message snapshot, or {@code null} if unavailable
     * @param responseSnapshot the response message snapshot, or {@code null} if unavailable
     * @return the {@link ExtractionResult} produced by the matching extractor
     */
    public ExtractionResult extract(
            ExtractRule rule,
            HttpMessageSnapshot requestSnapshot,
            HttpMessageSnapshot responseSnapshot) {

        Objects.requireNonNull(rule, "rule");

        HttpMessageSnapshot snapshot = resolveSnapshot(rule, requestSnapshot, responseSnapshot);
        if (snapshot == null) {
            return ExtractionResult.failure(rule.targetVariableName(),
                    "No " + rule.sourceSide().name().toLowerCase() + " message available");
        }

        ExtractionResult extracted;
        if (rule.extractorType() == ExtractRule.ExtractorType.REGEX) {
            RegexConfiguration regex = resolveRegexConfiguration(rule);
            extracted = regexExtractor.extract(
                    regexInput(snapshot, rule.regexSource()),
                    regex.pattern(),
                    regex.groupIndex(),
                    rule.targetVariableName());
        } else {
            extracted = switch (rule.extractorType()) {
                case HEADER -> extractHeader(rule, snapshot);
                case COOKIE -> extractCookie(rule, snapshot);
                case JSON_PATH -> jsonBodyExtractor.extract(snapshot.body(), rule.sourceSelector(), rule.targetVariableName());
                case BODY_TEXT -> ExtractionResult.success(rule.targetVariableName(), snapshot.body());
                case STATUS_CODE -> extractStatusCode(rule, snapshot);
                default -> throw new IllegalStateException("Unhandled extractor type: " + rule.extractorType());
            };
        }
        return applyTransforms(rule, extracted);
    }

    private HttpMessageSnapshot resolveSnapshot(
            ExtractRule rule,
            HttpMessageSnapshot requestSnapshot,
            HttpMessageSnapshot responseSnapshot) {
        return switch (rule.sourceSide()) {
            case REQUEST -> requestSnapshot;
            case RESPONSE -> responseSnapshot;
        };
    }

    private ExtractionResult extractHeader(ExtractRule rule, HttpMessageSnapshot snapshot) {
        return headerExtractor.extract(snapshot.headers(), rule.sourceSelector(), rule.targetVariableName());
    }

    private ExtractionResult extractCookie(ExtractRule rule, HttpMessageSnapshot snapshot) {
        boolean isResponse = rule.sourceSide() == ExtractRule.SourceSide.RESPONSE;
        String cookieHeaderName = isResponse ? "Set-Cookie" : "Cookie";

        ExtractionResult headerResult = headerExtractor.extract(
                snapshot.headers(), cookieHeaderName, rule.targetVariableName());

        if (!headerResult.isSuccess()) {
            return ExtractionResult.failure(rule.targetVariableName(),
                    cookieHeaderName + " header not found");
        }

        // For Set-Cookie response headers, only the first segment (before the first ';') is the
        // actual cookie name=value pair. Subsequent segments are cookie attributes (HttpOnly,
        // Path=/, SameSite=Strict, etc.) and must not be treated as cookies.
        String cookieSource = isResponse
                ? headerResult.value().split(";", 2)[0]
                : headerResult.value();

        return cookieExtractor.extract(cookieSource, rule.sourceSelector(), rule.targetVariableName());
    }

    private ExtractionResult extractStatusCode(ExtractRule rule, HttpMessageSnapshot snapshot) {
        String statusLine = snapshot.statusLine();
        if (statusLine == null || statusLine.isBlank()) {
            return ExtractionResult.failure(rule.targetVariableName(), "Status line is empty");
        }

        String[] parts = statusLine.strip().split("\\s+", 3);
        if (parts.length < 2) {
            return ExtractionResult.failure(rule.targetVariableName(),
                    "Cannot parse status code from status line: " + statusLine);
        }

        return ExtractionResult.success(rule.targetVariableName(), parts[1]);
    }

    /**
     * Resolves the regex pattern and capture group. New rules store the group separately;
     * persisted legacy rules may still encode it as a final {@code |N} suffix.
     */
    private RegexConfiguration resolveRegexConfiguration(ExtractRule rule) {
        String selector = rule.sourceSelector();
        if (rule.regexGroupIndex() != null) {
            return new RegexConfiguration(selector, rule.regexGroupIndex());
        }
        // Legacy rules encoded the group as the final "|N" suffix. New rules store it
        // separately so normal regex alternation remains unambiguous.
        int separator = selector == null ? -1 : selector.lastIndexOf('|');
        if (separator >= 0 && separator < selector.length() - 1) {
            String suffix = selector.substring(separator + 1).strip();
            try {
                int group = Integer.parseInt(suffix);
                if (group >= 0) {
                    return new RegexConfiguration(selector.substring(0, separator), group);
                }
            } catch (NumberFormatException ignored) {
                // It is regex alternation, not a legacy group suffix.
            }
        }
        return new RegexConfiguration(selector, 0);
    }

    private String regexInput(HttpMessageSnapshot snapshot, ExtractRule.RegexSource source) {
        String headerBlock = snapshot.statusLine()
                + (snapshot.headers().isEmpty() ? "" : "\r\n" + String.join("\r\n", snapshot.headers()));
        return switch (source) {
            case BODY -> snapshot.body();
            case HEADERS -> headerBlock;
            case FULL_MESSAGE -> headerBlock + "\r\n\r\n" + snapshot.body();
        };
    }

    private ExtractionResult applyTransforms(ExtractRule rule, ExtractionResult extracted) {
        if (!extracted.isSuccess() || rule.transformChain().isEmpty()) {
            return extracted;
        }
        ValueTransformResult transformed = transformService.apply(extracted.value(), rule.transformChain());
        if (!transformed.success()) {
            return ExtractionResult.failure(rule.targetVariableName(), transformed.error());
        }
        return ExtractionResult.success(rule.targetVariableName(), transformed.value());
    }

    private record RegexConfiguration(String pattern, int groupIndex) { }
}
