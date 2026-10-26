package dev.nquangit.corestra.http.discovery;

import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative heuristics that reduce payload pollution; they cannot establish traffic provenance. */
public final class WordlistNoiseFilter {
    private static final Pattern PAYLOAD = Pattern.compile("(?is)(<\\s*(script|svg|iframe)\\b|javascript\\s*:|onerror\\s*=|union\\s+(all\\s+)?select|\\b(or|and)\\s+['\"0-9]+\\s*=|\\b(sleep|benchmark|pg_sleep)\\s*\\(|\\$\\{jndi:|\\.\\.[/\\\\]|\\{\\{\\s*[0-9]+\\s*[*+]\\s*[0-9]+|burpcollaborator\\.net|\\.oast(?:ify)?\\.(?:com|net|online|site)|\\b(?:acunetix|nessus|nikto|nuclei)\\b)");
    /** Rejects obvious payload-bearing requests and very large request bodies before parsing fields. */
    public boolean accepts(HttpRequest request) {
        if (request.body().length() > 1_048_576) return false;
        if (tainted(request.path())) return false;
        for (var header : request.headers()) if (tainted(header.name()) || tainted(header.value())) return false;
        String type = request.headerValue("Content-Type");
        if (type != null && (type.toLowerCase(Locale.ROOT).contains("json") || type.toLowerCase(Locale.ROOT).contains("text")
                || type.toLowerCase(Locale.ROOT).contains("form-urlencoded") || type.toLowerCase(Locale.ROOT).contains("xml")))
            return !tainted(request.bodyToString());
        return true;
    }
    /** Recognizes common raw, once-encoded and twice-encoded test payload patterns. */
    public boolean tainted(String value) {
        if (value == null) return false;
        String text = value;
        for (int pass = 0; pass < 3; pass++) {
            if (PAYLOAD.matcher(text).find() || text.indexOf('\0') >= 0) return true;
            try { String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8); if (decoded.equals(text)) break; text = decoded; }
            catch (IllegalArgumentException malformed) { break; }
        }
        return false;
    }
    /** Allows structured parameter/header names without payload punctuation or line breaks. */
    public boolean cleanName(String name) {
        return name != null && name.matches("[\\p{L}_$][\\p{L}\\p{N}_.$:@\\[\\]-]{0,95}") && !tainted(name);
    }
    /** Detects likely IDs/tokens rather than useful path vocabulary. */
    public boolean dynamicSegment(String value) {
        return value.length() > 64 || value.matches("[0-9]+") || value.matches("(?i)[0-9a-f]{16,}")
                || value.matches("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*") || value.matches("[A-Za-z0-9_-]{25,}")
                || value.matches("(?i)(?:.*[._-])?[0-9a-f]{16,}\\.[a-z]{1,12}");
    }
}
