package dev.nquangit.corestra.integration.watch;

import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a Watch probe template into raw HTTP/1.1 request text. Montoya-free.
 *
 * <ul>
 *   <li>{@code {{name}}} placeholders in the path, header lines and body are replaced literally from
 *       the variables; any unresolved placeholder fails the render, naming every missing one.
 *       Line breaks in values rendered into the path or headers are removed.</li>
 *   <li>Inherited header lines replace template headers with the same name (case-insensitive) or
 *       are added; {@code Host} and {@code Content-Length} are never inherited.</li>
 *   <li>{@code Host} is kept from the template or added from host/port; {@code Content-Length} is
 *       recomputed from the rendered body (UTF-8).</li>
 * </ul>
 */
public final class WatchProbeRequestRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*\\}\\}");

    private WatchProbeRequestRenderer() { }

    /**
     * Rendered probe.
     *
     * @param host       target host
     * @param port       target port
     * @param secure     whether TLS is used
     * @param rawRequest raw request text with CRLF line endings
     */
    public record Rendered(String host, int port, boolean secure, String rawRequest) { }

    /**
     * Renders the template.
     *
     * @param template         probe template
     * @param variables        variables for placeholders
     * @param inheritedHeaders {@code "Name: value"} lines from the live request
     * @return rendered request
     * @throws IllegalArgumentException when a placeholder is unresolved (message names them)
     */
    public static Rendered render(StoredRequestTemplate template, Map<String, String> variables,
                                  List<String> inheritedHeaders) {
        Objects.requireNonNull(template, "template");
        Map<String, String> vars = variables == null ? Map.of() : variables;
        Set<String> missing = new LinkedHashSet<>();
        collectMissing(template.pathAndQuery(), vars, missing);
        template.headerLines().forEach(line -> collectMissing(line, vars, missing));
        collectMissing(template.body(), vars, missing);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("unresolved probe placeholder(s): "
                    + String.join(", ", missing.stream().map(name -> "{{" + name + "}}").toList()));
        }

        String path = singleLine(substitute(template.pathAndQuery(), vars, true));
        if (path.isBlank()) path = "/";
        String body = substitute(template.body(), vars, false);

        List<String> inherited = new ArrayList<>();
        Set<String> inheritedNames = new LinkedHashSet<>();
        for (String line : inheritedHeaders == null ? List.<String>of() : inheritedHeaders) {
            String name = headerName(line);
            if (name == null || name.equals("host") || name.equals("content-length")) continue;
            inherited.add(singleLine(line));
            inheritedNames.add(name);
        }

        List<String> headers = new ArrayList<>();
        boolean hasHost = false;
        for (String line : template.headerLines()) {
            String name = headerName(line);
            if (name == null || name.equals("content-length") || inheritedNames.contains(name)) continue;
            if (name.equals("host")) hasHost = true;
            headers.add(singleLine(substitute(line, vars, true)));
        }
        if (!hasHost) headers.addFirst("Host: " + hostHeader(template));
        headers.addAll(inherited);
        int length = body.getBytes(StandardCharsets.UTF_8).length;
        if (length > 0) headers.add("Content-Length: " + length);

        StringBuilder raw = new StringBuilder();
        raw.append(template.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
        for (String header : headers) raw.append(header).append("\r\n");
        raw.append("\r\n").append(body);
        return new Rendered(template.host(), template.port(), "https".equalsIgnoreCase(template.scheme()), raw.toString());
    }

    private static void collectMissing(String text, Map<String, String> vars, Set<String> missing) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (vars.get(matcher.group(1)) == null) missing.add(matcher.group(1));
        }
    }

    private static String substitute(String text, Map<String, String> vars, boolean singleLineValues) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1));
            if (singleLineValues) value = singleLine(value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String singleLine(String text) {
        return text.replace("\r", "").replace("\n", "");
    }

    private static String headerName(String line) {
        if (line == null) return null;
        int colon = line.indexOf(':');
        if (colon <= 0) return null;
        return line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
    }

    private static String hostHeader(StoredRequestTemplate template) {
        boolean defaultPort = ("https".equalsIgnoreCase(template.scheme()) && template.port() == 443)
                || ("http".equalsIgnoreCase(template.scheme()) && template.port() == 80);
        return defaultPort ? template.host() : template.host() + ":" + template.port();
    }
}
