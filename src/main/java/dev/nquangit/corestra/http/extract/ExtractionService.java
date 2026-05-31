package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.Objects;
import java.util.OptionalInt;

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

    /**
     * Creates an {@code ExtractionService} with default extractor instances.
     */
    public ExtractionService() {
        this.headerExtractor = new HeaderExtractor();
        this.cookieExtractor = new CookieExtractor();
        this.jsonBodyExtractor = new JsonBodyExtractor();
        this.regexExtractor = new RegexExtractor();
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

        if (rule.extractorType() == ExtractRule.ExtractorType.REGEX) {
            OptionalInt groupIndex = resolveGroupIndex(rule);
            if (groupIndex.isEmpty()) {
                String raw = rule.sourceSelector() == null ? "" : rule.sourceSelector();
                String badPart = raw.contains("|") ? raw.substring(raw.indexOf('|') + 1).strip() : raw;
                return ExtractionResult.failure(rule.targetVariableName(),
                        "invalid group index: '" + badPart + "'");
            }
            return regexExtractor.extract(snapshot.body(), rule.sourceSelector(), groupIndex.getAsInt(), rule.targetVariableName());
        }

        return switch (rule.extractorType()) {
            case HEADER -> extractHeader(rule, snapshot);
            case COOKIE -> extractCookie(rule, snapshot);
            case JSON_PATH -> jsonBodyExtractor.extract(snapshot.body(), rule.sourceSelector(), rule.targetVariableName());
            case BODY_TEXT -> ExtractionResult.success(rule.targetVariableName(), snapshot.body());
            case STATUS_CODE -> extractStatusCode(rule, snapshot);
            default -> throw new IllegalStateException("Unhandled extractor type: " + rule.extractorType());
        };
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
     * Parses the optional group-index suffix from a REGEX {@code sourceSelector}.
     *
     * <p>Returns {@code OptionalInt.of(n)} when the selector has no pipe suffix (defaults to
     * group 0) or when the part after {@code '|'} is a valid non-negative integer.
     * Returns {@code OptionalInt.empty()} when the part after {@code '|'} is present but
     * cannot be parsed as an integer, signalling a misconfiguration that the caller must
     * surface as a failure result.
     */
    private OptionalInt resolveGroupIndex(ExtractRule rule) {
        String selector = rule.sourceSelector();
        if (selector == null || selector.isBlank()) {
            return OptionalInt.of(0);
        }
        String[] parts = selector.split("\\|", 2);
        if (parts.length == 2) {
            try {
                return OptionalInt.of(Integer.parseInt(parts[1].strip()));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(0);
    }
}
