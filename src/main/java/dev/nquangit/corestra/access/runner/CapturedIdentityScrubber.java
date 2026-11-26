package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Removes identity material that was captured together with a request before an actor's own
 * authentication is applied.
 *
 * <p>Access tests must send each request strictly as the tested actor. Captured requests usually
 * still carry the session cookies, bearer tokens or API keys of whoever browsed the application;
 * leaving them in place makes another actor's request look authorized (false SUSPICIOUS) or keeps
 * extra identity cookies such as {@code remember_me} that the actor's auth never overrides.</p>
 *
 * <p>Only identity material is removed: credential headers, token query parameters, cookies whose
 * names look like session/auth cookies, and every header, cookie or parameter name the actor's
 * own auth will set. Other cookies (CSRF double-submit, locale, load-balancer, bot-protection) are
 * kept. Values containing a template placeholder ({@code {{...}}}) were written by the template
 * author on purpose and are always preserved.</p>
 */
public final class CapturedIdentityScrubber {
    private static final Set<String> DEFAULT_HEADERS = Set.of(
            "authorization",
            "x-api-key",
            "x-auth-token",
            "x-access-token",
            "x-session-token");
    private static final Set<String> DEFAULT_QUERY_PARAMETERS = Set.of(
            "access_token",
            "api_key",
            "apikey",
            "auth_token");
    /** Words that mark a cookie as carrying identity, matched on whole name parts. */
    private static final Set<String> IDENTITY_COOKIE_WORDS = Set.of(
            "session", "sessionid", "sess", "sid", "jsessionid", "phpsessid", "auth", "authtoken", "token",
            "accesstoken", "refreshtoken", "idtoken", "jwt", "remember", "rememberme", "login", "identity",
            "access", "refresh", "bearer");

    private final Set<String> headerNames;
    private final Set<String> queryParameterNames;
    private final Set<String> cookieNames;

    /**
     * Creates a scrubber with the default names only.
     */
    public CapturedIdentityScrubber() {
        this(Set.of(), Set.of(), Set.of());
    }

    /**
     * Creates a scrubber that also removes extra names, such as those set by the actor's auth.
     *
     * @param extraHeaderNames additional header names to remove (case-insensitive)
     * @param extraQueryParameterNames additional query parameter names to remove (case-insensitive)
     * @param extraCookieNames additional cookie names to remove (case-insensitive)
     */
    public CapturedIdentityScrubber(
            Set<String> extraHeaderNames,
            Set<String> extraQueryParameterNames,
            Set<String> extraCookieNames) {
        this.headerNames = lowerCaseUnion(DEFAULT_HEADERS, extraHeaderNames);
        this.queryParameterNames = lowerCaseUnion(DEFAULT_QUERY_PARAMETERS, extraQueryParameterNames);
        this.cookieNames = lowerCaseUnion(Set.of(), extraCookieNames);
    }

    /**
     * Creates a scrubber for one actor: it also removes every header, query parameter and cookie
     * name the actor's provider rules or manual auth state will set, so a rule using
     * {@code SKIP_IF_PRESENT} or {@code APPEND} never keeps the captured identity.
     *
     * @param profile tested actor, or null
     * @param provider actor's auth provider when it is applied, or null
     * @return scrubber for that actor
     */
    public static CapturedIdentityScrubber forActor(AuthProfile profile, AuthProvider provider) {
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        LinkedHashSet<String> query = new LinkedHashSet<>();
        LinkedHashSet<String> cookies = new LinkedHashSet<>();
        if (provider != null) {
            for (AuthInjectionRule rule : provider.injectionRules()) {
                String target = Objects.requireNonNullElse(rule.target(), "").strip();
                if (target.isEmpty() || hasPlaceholder(target)) {
                    continue;
                }
                AuthInjectionLocation location = Objects.requireNonNullElse(rule.location(), AuthInjectionLocation.HEADER);
                switch (location) {
                    case HEADER -> {
                        if (!"cookie".equalsIgnoreCase(target)) {
                            headers.add(target);
                        }
                    }
                    case QUERY -> query.add(target);
                    case COOKIE -> cookies.add(target);
                    default -> {
                    }
                }
            }
        }
        if (profile != null && profile.state() != null) {
            profile.state().cookies().keySet().forEach(name -> {
                if (!hasPlaceholder(name)) {
                    cookies.add(name);
                }
            });
            profile.state().headers().keySet().forEach(name -> {
                if (!hasPlaceholder(name) && !"cookie".equalsIgnoreCase(name)) {
                    headers.add(name);
                }
            });
        }
        return new CapturedIdentityScrubber(headers, query, cookies);
    }

    /**
     * Returns a copy of an access template whose base request no longer carries captured identity.
     *
     * @param template access request template
     * @return scrubbed template, or the same instance when nothing was removed
     */
    public AccessRequestTemplate scrub(AccessRequestTemplate template) {
        Objects.requireNonNull(template, "template");
        StoredRequestTemplate scrubbed = scrub(template.baseRequest());
        if (scrubbed == template.baseRequest()) {
            return template;
        }
        return new AccessRequestTemplate(
                template.id(),
                template.name(),
                scrubbed,
                template.placeholders(),
                template.operationMetadata(),
                template.sourceFlowId(),
                template.sourceStepId(),
                template.sourceRequestHash(),
                template.enabled());
    }

    /**
     * Returns a copy of the request without captured identity material.
     *
     * @param request rendered or template request
     * @return scrubbed request, or the same instance when nothing was removed
     */
    public StoredRequestTemplate scrub(StoredRequestTemplate request) {
        Objects.requireNonNull(request, "request");
        boolean changed = false;
        List<String> headers = new ArrayList<>();
        for (String header : request.headerLines()) {
            int colon = header == null ? -1 : header.indexOf(':');
            if (colon <= 0) {
                headers.add(header);
                continue;
            }
            String name = header.substring(0, colon).strip();
            String value = header.substring(colon + 1).strip();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if ("cookie".equals(lowerName)) {
                String kept = keptCookies(value);
                if (!kept.equals(value)) {
                    changed = true;
                }
                if (!kept.isEmpty()) {
                    headers.add(name + ": " + kept);
                }
                continue;
            }
            if (headerNames.contains(lowerName) && !hasPlaceholder(value)) {
                changed = true;
                continue;
            }
            headers.add(header);
        }

        String pathAndQuery = scrubQuery(request.pathAndQuery());
        if (!pathAndQuery.equals(request.pathAndQuery())) {
            changed = true;
        }
        if (!changed) {
            return request;
        }
        return new StoredRequestTemplate(
                request.method(),
                request.scheme(),
                request.host(),
                request.port(),
                pathAndQuery,
                headers,
                request.body());
    }

    private String keptCookies(String value) {
        List<String> kept = new ArrayList<>();
        for (String pair : value.split(";")) {
            String trimmed = pair.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            String name = equals < 0 ? trimmed : trimmed.substring(0, equals).strip();
            if (hasPlaceholder(trimmed) || !isIdentityCookie(name)) {
                kept.add(trimmed);
            }
        }
        return String.join("; ", kept);
    }

    /**
     * Returns whether a cookie name carries identity: one of the actor's own auth cookie names or a
     * name made of session/auth words ({@code session}, {@code JSESSIONID}, {@code remember_me},
     * {@code access_token}). CSRF/XSRF cookies are never treated as identity.
     *
     * @param name cookie name
     * @return true when the captured value must not be sent as another actor
     */
    boolean isIdentityCookie(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (cookieNames.contains(lower)) {
            return true;
        }
        if (lower.contains("csrf") || lower.contains("xsrf")) {
            return false;
        }
        String compact = lower.replaceAll("[^a-z0-9]", "");
        if (IDENTITY_COOKIE_WORDS.contains(compact) || compact.contains("session")) {
            return true;
        }
        for (String word : lower.split("[^a-z0-9]+")) {
            if (IDENTITY_COOKIE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String scrubQuery(String pathAndQuery) {
        String target = Objects.requireNonNullElse(pathAndQuery, "");
        int queryStart = target.indexOf('?');
        if (queryStart < 0) {
            return target;
        }
        int fragmentStart = target.indexOf('#', queryStart);
        String query = fragmentStart < 0 ? target.substring(queryStart + 1) : target.substring(queryStart + 1, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : target.substring(fragmentStart);
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String pair : query.split("&", -1)) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            if (queryParameterNames.contains(decode(rawName).toLowerCase(Locale.ROOT)) && !hasPlaceholder(value)) {
                removed = true;
                continue;
            }
            kept.add(pair);
        }
        if (!removed) {
            return target;
        }
        String path = target.substring(0, queryStart);
        return (kept.isEmpty() ? path : path + "?" + String.join("&", kept)) + fragment;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static boolean hasPlaceholder(String value) {
        int open = value.indexOf("{{");
        return open >= 0 && value.indexOf("}}", open + 2) > open;
    }

    private static Set<String> lowerCaseUnion(Set<String> defaults, Set<String> extras) {
        LinkedHashSet<String> names = new LinkedHashSet<>(defaults);
        if (extras != null) {
            for (String extra : extras) {
                if (extra != null && !extra.isBlank()) {
                    names.add(extra.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(names);
    }
}
