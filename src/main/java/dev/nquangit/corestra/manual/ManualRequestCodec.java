package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.session.SessionExecutionBinding;
import java.net.URI;
import java.util.*;

/** Validates editable HTTP text separately from its explicit routing origin. */
public final class ManualRequestCodec {
    private ManualRequestCodec() { }
    /** Formats a request for the native editable message editor. */
    public static String format(StoredRequestTemplate t) {
        List<String> headers = new ArrayList<>(t.headerLines());
        if (headers.stream().noneMatch(h -> h.regionMatches(true, 0, "Host:", 0, 5))) {
            boolean usual = t.port() == (t.scheme().equalsIgnoreCase("https") ? 443 : 80);
            headers.addFirst("Host: " + t.host() + (usual ? "" : ":" + t.port()));
        }
        return t.method() + " " + t.pathAndQuery() + " HTTP/1.1\r\n" + String.join("\r\n", headers) + "\r\n\r\n" + t.body();
    }
    /** Returns the exact service origin, including port. */
    public static String origin(StoredRequestTemplate t) {
        String host = t.host(); if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return SessionExecutionBinding.normalizeOrigin(t.scheme() + "://" + host + ":" + t.port());
    }
    /** Parses HTTP/1.x text. Changing Host does not silently change the actual service destination. */
    public static StoredRequestTemplate parse(String origin, String raw) {
        URI uri = URI.create(SessionExecutionBinding.normalizeOrigin(origin));
        int boundary = raw.indexOf("\r\n\r\n"), skip = 4;
        if (boundary < 0) { boundary = raw.indexOf("\n\n"); skip = 2; }
        if (boundary < 0) throw new IllegalArgumentException("Request needs a blank line between headers and body");
        String[] lines = raw.substring(0, boundary).split("\\r?\\n", -1);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || first[0].isBlank() || first[1].isBlank() || !first[2].matches("HTTP/(1\\.[01]|2)"))
            throw new IllegalArgumentException("Use METHOD /path?query HTTP/1.1; request-line placeholders cannot contain spaces");
        List<String> headers = Arrays.stream(lines).skip(1).toList();
        if (headers.stream().anyMatch(h -> h.indexOf(':') < 1)) throw new IllegalArgumentException("Each header needs Name: value");
        return new StoredRequestTemplate(first[0], uri.getScheme(), uri.getHost(), uri.getPort(), first[1], headers, raw.substring(boundary + skip));
    }
}
