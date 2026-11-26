package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CapturedIdentityScrubberTest {
    private final CapturedIdentityScrubber scrubber = new CapturedIdentityScrubber();

    @Test
    void removesCapturedCookiesAndAuthorizationButKeepsOtherHeaders() {
        StoredRequestTemplate scrubbed = scrubber.scrub(request("/orders/7", List.of(
                "Host: example.test",
                "Authorization: Bearer admin-token",
                "Cookie: session=admin; remember_me=admin-remember",
                "X-Trace: one"), ""));

        assertEquals(List.of("Host: example.test", "X-Trace: one"), scrubbed.headerLines());
    }

    @Test
    void keepsTemplatedIdentityAndNonIdentityCookies() {
        StoredRequestTemplate scrubbed = scrubber.scrub(request("/orders/7", List.of(
                "Authorization: Bearer {{auth.accessToken}}",
                "Cookie: session=admin; token={{auth.t}}; theme=dark; XSRF-TOKEN=x1; __cf_bm=cf; JSESSIONID=j;"
                        + " connect.sid=c; laravel_session=l; access_token=a; lang=en"), ""));

        assertEquals(List.of(
                "Authorization: Bearer {{auth.accessToken}}",
                "Cookie: token={{auth.t}}; theme=dark; XSRF-TOKEN=x1; __cf_bm=cf; lang=en"), scrubbed.headerLines());
    }

    @Test
    void actorScrubberAlsoRemovesNamesTheActorsAuthWillSet() {
        dev.nquangit.corestra.access.auth.AuthProfile actor = new dev.nquangit.corestra.access.auth.AuthProfile(
                "a1", "Alice", "user", "manual", java.util.Map.of(),
                new dev.nquangit.corestra.access.auth.AuthState(java.util.Map.of(),
                        java.util.Map.of("X-Tenant-Auth", "{{auth.t}}"), java.util.Map.of("prefs_id", "p"), 0, 0),
                true);

        StoredRequestTemplate scrubbed = CapturedIdentityScrubber.forActor(actor, null).scrub(request("/a",
                List.of("X-Tenant-Auth: captured", "Cookie: prefs_id=captured; theme=dark"), ""));

        assertEquals(List.of("Cookie: theme=dark"), scrubbed.headerLines());
    }

    @Test
    void removesWellKnownTokenHeadersCaseInsensitively() {
        StoredRequestTemplate scrubbed = scrubber.scrub(request("/", List.of(
                "x-api-key: k1",
                "X-Auth-Token: t1",
                "X-Access-Token: t2",
                "Proxy-Authorization: Basic keep",
                "Accept: */*"), ""));

        assertEquals(List.of("Proxy-Authorization: Basic keep", "Accept: */*"), scrubbed.headerLines());
    }

    @Test
    void removesCapturedTokenQueryParametersOnly() {
        StoredRequestTemplate scrubbed = scrubber.scrub(request(
                "/api/items?page=2&access_token=abc&api_key=k&id={{data.id}}&token={{auth.t}}",
                List.of(), ""));

        assertEquals("/api/items?page=2&id={{data.id}}&token={{auth.t}}", scrubbed.pathAndQuery());
    }

    @Test
    void dropsQuestionMarkWhenEveryParameterIsRemoved() {
        StoredRequestTemplate scrubbed = scrubber.scrub(request("/api/items?access_token=abc", List.of(), ""));

        assertEquals("/api/items", scrubbed.pathAndQuery());
    }

    @Test
    void extraHeaderAndQueryNamesFromProvidersAreScrubbed() {
        CapturedIdentityScrubber custom = new CapturedIdentityScrubber(
                java.util.Set.of("X-Tenant-Session"), java.util.Set.of("sid"), java.util.Set.of());
        StoredRequestTemplate scrubbed = custom.scrub(request("/a?sid=1&q=2",
                List.of("X-Tenant-Session: s", "Accept: */*"), "body"));

        assertEquals(List.of("Accept: */*"), scrubbed.headerLines());
        assertEquals("/a?q=2", scrubbed.pathAndQuery());
        assertEquals("body", scrubbed.body());
    }

    @Test
    void returnsSameInstanceWhenNothingToScrub() {
        StoredRequestTemplate original = request("/a?q=1", List.of("Accept: */*"), "");

        assertSame(original, scrubber.scrub(original));
    }

    private static StoredRequestTemplate request(String pathAndQuery, List<String> headers, String body) {
        return new StoredRequestTemplate("GET", "https", "example.test", 443, pathAndQuery, headers, body);
    }
}
