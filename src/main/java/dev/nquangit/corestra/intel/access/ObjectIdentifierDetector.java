package dev.nquangit.corestra.intel.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.intel.RawHttpMessages;
import dev.nquangit.corestra.intel.access.IdentifierOccurrence.Kind;
import dev.nquangit.corestra.intel.access.IdentifierOccurrence.Location;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds object identifiers (IDOR/BOLA candidates) in a request's path, query, form and JSON body.
 *
 * <p>Path segments are judged by shape alone. Query, form and JSON values need an identifier-like
 * name ({@code id}, {@code userId}, {@code account_id}, {@code uuid} …) or a strongly identifier-like
 * shape (UUID, MongoDB ObjectId). Already-parameterized placeholders are reported too, so callers can
 * tell a template that is ready for a Data Profile from one that still carries literal values.</p>
 */
public final class ObjectIdentifierDetector {
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OBJECT_ID = Pattern.compile("(?i)[0-9a-f]{24}");
    private static final Pattern HEX = Pattern.compile("(?i)[0-9a-f]{16,}");
    private static final Pattern NUMERIC = Pattern.compile("[0-9]{1,19}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s/]+@[^@\\s/]+\\.[A-Za-z]{2,}");
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{10,64}");
    private static final Pattern SHORT_CODE = Pattern.compile("[A-Za-z0-9_-]{3,64}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");
    private static final Pattern VERSION_SEGMENT = Pattern.compile("(?i)v[0-9]+(\\.[0-9]+)*");
    private static final Set<String> ID_WORDS = Set.of(
            "id", "uuid", "guid", "uid", "pk", "key", "ref", "number", "no", "num", "code");
    private static final Set<String> OBJECT_WORDS = Set.of(
            "user", "account", "order", "invoice", "document", "doc", "file", "customer", "tenant", "org",
            "organization", "organisation", "company", "workspace", "project", "team", "member", "profile",
            "payment", "transaction", "ticket", "message", "report", "item", "product", "cart", "address",
            "card", "wallet", "branch", "group", "owner", "employee", "patient", "record", "booking", "contract");
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_RESULTS = 200;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Detects identifiers in a request.
     *
     * @param request request template
     * @return identifiers in path, query, form, then JSON order
     */
    public List<IdentifierOccurrence> detect(StoredRequestTemplate request) {
        Objects.requireNonNull(request, "request");
        List<IdentifierOccurrence> found = new ArrayList<>();
        String pathAndQuery = Objects.requireNonNullElse(request.pathAndQuery(), "");
        int queryStart = pathAndQuery.indexOf('?');
        String path = queryStart < 0 ? pathAndQuery : pathAndQuery.substring(0, queryStart);
        String[] segments = path.startsWith("/") ? path.substring(1).split("/", -1) : new String[0];
        for (int index = 0; index < segments.length; index++) {
            String segment = decode(segments[index]);
            Kind kind = pathKind(segment);
            if (kind != null) {
                String previous = index > 0 ? decode(segments[index - 1]) : "";
                found.add(new IdentifierOccurrence(Location.PATH, String.valueOf(index),
                        nameFromPath(previous, segment), segment, kind, kind == Kind.PLACEHOLDER));
            }
        }
        if (queryStart >= 0) {
            String query = pathAndQuery.substring(queryStart + 1);
            int fragment = query.indexOf('#');
            pairs(fragment < 0 ? query : query.substring(0, fragment), Location.QUERY, found);
        }
        String contentType = RawHttpMessages.header(request.headerLines(), "Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
        String body = Objects.requireNonNullElse(request.body(), "");
        if (contentType.contains("application/x-www-form-urlencoded")) {
            pairs(body, Location.FORM, found);
        } else if (contentType.contains("json") || body.stripLeading().startsWith("{") || body.stripLeading().startsWith("[")) {
            try {
                walk(json.readTree(body), "", "", found, 0);
            } catch (Exception ignored) {
                // Bodies with unquoted placeholders ({"id": {{data.id}}}) are not JSON; scan them textually.
                scanTextualJson(body, found);
            }
        }
        return found.size() > MAX_RESULTS ? List.copyOf(found.subList(0, MAX_RESULTS)) : List.copyOf(found);
    }

    private void walk(JsonNode node, String path, String fieldName, List<IdentifierOccurrence> found, int depth) {
        if (node == null || depth > MAX_JSON_DEPTH || found.size() > MAX_RESULTS) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey();
                if (key.contains(".") || key.contains("[") || key.contains("]")) {
                    continue;
                }
                walk(field.getValue(), path.isEmpty() ? key : path + "." + key, key, found, depth + 1);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                walk(node.get(index), path + "[" + index + "]", fieldName, found, depth + 1);
            }
        } else if (node.isTextual() || node.isIntegralNumber()) {
            classifyNamed(Location.JSON, path, fieldName, node.asText(), found);
        }
    }

    private static void scanTextualJson(String body, List<IdentifierOccurrence> found) {
        var matcher = Pattern.compile("\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*(\\{\\{[^{}]+}}|\"\\{\\{[^{}]+}}\")").matcher(body);
        while (matcher.find() && found.size() <= MAX_RESULTS) {
            String key = matcher.group(1);
            String value = matcher.group(2).replace("\"", "");
            found.add(new IdentifierOccurrence(Location.JSON, key, key, value, Kind.PLACEHOLDER, true));
        }
    }

    private static void pairs(String encoded, Location location, List<IdentifierOccurrence> found) {
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = decode(pair.substring(0, equals));
            classifyNamed(location, name, name, decode(pair.substring(equals + 1)), found);
        }
    }

    private static void classifyNamed(Location location, String selector, String fieldName, String value,
                                      List<IdentifierOccurrence> found) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (PLACEHOLDER.matcher(value).matches()) {
            if (isIdentifierName(fieldName)) {
                found.add(new IdentifierOccurrence(location, selector, fieldName, value, Kind.PLACEHOLDER, true));
            }
            return;
        }
        Kind shape = valueKind(value);
        if (shape == null && SHORT_CODE.matcher(value).matches() && value.chars().anyMatch(Character::isDigit)) {
            shape = Kind.OPAQUE; // short business keys such as "A-1009" count only under an identifier-like name
        }
        boolean strongShape = shape == Kind.UUID || shape == Kind.OBJECT_ID;
        if (shape != null && (strongShape || isIdentifierName(fieldName))) {
            found.add(new IdentifierOccurrence(location, selector, fieldName, value, shape, false));
        }
    }

    static boolean isIdentifierName(String name) {
        List<String> words = words(name);
        if (words.isEmpty()) {
            return false;
        }
        String last = words.getLast();
        if (ID_WORDS.contains(last) && (words.size() > 1 || last.equals("id") || last.equals("uuid")
                || last.equals("guid") || last.equals("uid"))) {
            return true;
        }
        return words.size() == 1 && OBJECT_WORDS.contains(last);
    }

    private static Kind pathKind(String segment) {
        if (segment.isEmpty()) {
            return null;
        }
        if (PLACEHOLDER.matcher(segment).matches()) {
            return Kind.PLACEHOLDER;
        }
        if (VERSION_SEGMENT.matcher(segment).matches()) {
            return null;
        }
        Kind kind = valueKind(segment);
        if (kind == Kind.OPAQUE) {
            // Words such as "notifications" or "change-password" are routes, not identifiers.
            boolean hasDigit = segment.chars().anyMatch(Character::isDigit);
            boolean hasLetter = segment.chars().anyMatch(Character::isLetter);
            return hasDigit && hasLetter ? Kind.OPAQUE : null;
        }
        return kind;
    }

    private static Kind valueKind(String value) {
        if (UUID_SHAPE.matcher(value).matches()) {
            return Kind.UUID;
        }
        if (OBJECT_ID.matcher(value).matches()) {
            return Kind.OBJECT_ID;
        }
        if (NUMERIC.matcher(value).matches()) {
            return Kind.NUMERIC;
        }
        if (HEX.matcher(value).matches()) {
            return Kind.HEX;
        }
        if (EMAIL.matcher(value).matches()) {
            return Kind.EMAIL;
        }
        if (OPAQUE.matcher(value).matches()) {
            return Kind.OPAQUE;
        }
        return null;
    }

    private static String nameFromPath(String previousSegment, String value) {
        if (PLACEHOLDER.matcher(value).matches()) {
            var matcher = PLACEHOLDER.matcher(value);
            matcher.matches();
            String key = matcher.group(1);
            int dot = key.lastIndexOf('.');
            return dot >= 0 ? key.substring(dot + 1) : key;
        }
        List<String> words = words(previousSegment);
        if (words.isEmpty() || pathKind(previousSegment) != null) {
            return "id";
        }
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            String singular = singular(word);
            name.append(name.isEmpty() ? singular : Character.toUpperCase(singular.charAt(0)) + singular.substring(1));
        }
        return name + "Id";
    }

    private static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("ses") || word.endsWith("xes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /**
     * Splits a name into lower-case words on separators and camelCase boundaries.
     *
     * @param name name
     * @return words
     */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char previous = 0;
        String value = Objects.requireNonNullElse(name, "");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!Character.isLetterOrDigit(ch)) {
                flush(current, words);
            } else {
                if (Character.isUpperCase(ch) && Character.isLowerCase(previous)) {
                    flush(current, words);
                }
                current.append(Character.toLowerCase(ch));
            }
            previous = ch;
        }
        flush(current, words);
        return words;
    }

    private static void flush(StringBuilder current, List<String> words) {
        if (!current.isEmpty()) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }
}
