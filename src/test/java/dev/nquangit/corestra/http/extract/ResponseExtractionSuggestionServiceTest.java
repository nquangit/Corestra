package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponseExtractionSuggestionServiceTest {
    private final ResponseExtractionSuggestionService service = new ResponseExtractionSuggestionService();
    private final ExtractionService extraction = new ExtractionService();

    private static HttpMessageSnapshot response(String body, String... headers) {
        return new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(headers), body);
    }
    private static ExtractRule existing(ExtractRule.SourceSide side, ExtractRule.ExtractorType type, String selector, String key) {
        return new ExtractRule(UUID.randomUUID().toString(), "Existing", true, side, type, selector, key, List.of());
    }

    @Test void setCookiesAreSeparateAndAttributesNeverBecomeCookies() {
        var sample = response("", "Set-Cookie: JSESSIONID=abc=def; Path=/; HttpOnly", "set-cookie: token=first; Secure",
                "Set-Cookie: token=second; Path=/other", "Set-Cookie: deleted=; Max-Age=0", "X-Trace: one", "X-Trace: two");
        var candidates = service.analyze(sample, List.of()).candidates();
        var cookies = candidates.stream().filter(c -> c.type() == ExtractRule.ExtractorType.COOKIE).toList();
        assertEquals(List.of("JSESSIONID", "token", "deleted"), cookies.stream().map(ResponseExtractionSuggestionService.Candidate::selector).toList());
        assertEquals("abc=def", cookies.getFirst().preview()); assertEquals("first", cookies.get(1).preview()); assertEquals("", cookies.get(2).preview());
        assertEquals(1, candidates.stream().filter(c -> c.selector().equals("X-Trace")).count());
        for (var candidate : candidates) {
            var rule = candidate.toRule(candidate.variable()); assertEquals(ExtractRule.SourceSide.RESPONSE, rule.sourceSide());
            assertTrue(extraction.extract(rule, null, sample).isSuccess());
        }
    }

    @Test void jsonSuggestionsExecuteForArraysSpecialKeysContainersZeroAndFalse() {
        var sample = response("{\"data\":{\"items\":[{\"id\":0,\"ok\":false}]},\"a.b/~\":\"value\",\"\":\"empty-key\",\"none\":null}", "Content-Type: application/json");
        var candidates = service.analyze(sample, List.of()).candidates();
        Map<String,String> expected = Map.of("pointer:/data/items/0/id", "0", "pointer:/data/items/0/ok", "false",
                "pointer:/a.b~1~0", "value", "pointer:/", "empty-key", "pointer:/data/items", "[{\"id\":0,\"ok\":false}]");
        expected.forEach((selector,value) -> {
            var candidate = candidates.stream().filter(c -> c.selector().equals(selector)).findFirst().orElseThrow();
            assertEquals(value, extraction.extract(candidate.toRule(candidate.variable()), null, sample).value());
        });
        assertTrue(candidates.stream().noneMatch(c -> c.selector().equals("pointer:/none")));
        assertEquals(candidates.size(), candidates.stream().map(ResponseExtractionSuggestionService.Candidate::variable).distinct().count());
        var rootArray = service.analyze(response("[\"first\",null,2]"), List.of()).candidates();
        assertTrue(rootArray.stream().anyMatch(c -> c.selector().equals("pointer:/2") && c.preview().equals("2")));
    }

    @Test void skipsExistingSourcesIncludingLegacyDotPathsButKeepsRequestAndResponseDistinct() {
        var old = List.of(existing(ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.JSON_PATH, "data.token", "saved"),
                existing(ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.HEADER, "x-trace", "trace"),
                existing(ExtractRule.SourceSide.REQUEST, ExtractRule.ExtractorType.COOKIE, "token", "requestToken"));
        var candidates = service.analyze(response("{\"data\":{\"token\":\"new\"}}", "X-Trace: first", "Set-Cookie: token=cookie"), old).candidates();
        assertTrue(candidates.stream().noneMatch(c -> c.selector().equals("pointer:/data/token") || c.selector().equals("X-Trace")));
        assertTrue(candidates.stream().anyMatch(c -> c.type() == ExtractRule.ExtractorType.COOKIE && c.selector().equals("token")));
    }

    @Test void applyValidatesVariableNamesCollisionsAndIsAtomicAndIdempotent() {
        var sample = response("", "Set-Cookie: token=one", "Set-Cookie: other=two");
        var candidates = service.analyze(sample, List.of()).candidates();
        var first = candidates.get(0).toRule("chosen"); var second = candidates.get(1).toRule("chosen");
        List<ExtractRule> original = new ArrayList<>(List.of(first));
        assertEquals(original, service.merge(original, List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> service.merge(original, List.of(second)));
        assertEquals(List.of(first), original);
        for (String name : List.of("", "has space", "{{value}}", "value|trim")) assertThrows(IllegalArgumentException.class, () -> candidates.getFirst().toRule(name));
        var requestRule = existing(ExtractRule.SourceSide.REQUEST, ExtractRule.ExtractorType.HEADER, "X-Test", "x");
        assertThrows(IllegalArgumentException.class, () -> service.merge(List.of(), List.of(requestRule)));
    }

    @Test void invalidOrOversizedJsonKeepsHeadersAndShowsTheLimit() {
        var invalid = service.analyze(response("{broken", "X-Trace: works"), List.of());
        assertTrue(invalid.candidates().stream().anyMatch(c -> c.selector().equals("X-Trace")));
        assertTrue(invalid.notes().stream().anyMatch(n -> n.contains("could not")));
        var large = service.analyze(response("{\"payload\":\"" + "x".repeat(ResponseExtractionSuggestionService.MAX_JSON_CHARS) + "\"}", "Set-Cookie: token=ok"), List.of());
        assertTrue(large.candidates().stream().noneMatch(c -> c.type() == ExtractRule.ExtractorType.JSON_PATH));
        assertTrue(large.notes().stream().anyMatch(n -> n.contains("exceeds")));
        var many = service.analyze(response("[" + String.join(",", Collections.nCopies(1000, "0")) + "]"), List.of());
        assertTrue(many.candidates().size() <= ResponseExtractionSuggestionService.MAX_CANDIDATES);
        assertTrue(many.notes().stream().anyMatch(n -> n.contains("limited")));
    }

    @Test void legacyDotSelectorsKeepTheirBehaviorAndInvalidPointersFailCleanly() {
        var extractor = new JsonBodyExtractor(); String body = "{\"data\":{\"token\":\"abc\"},\"values\":[1]}";
        assertEquals("abc", extractor.extract(body,"data.token","out").value());
        assertEquals("", extractor.extract(body,"data","out").value());
        assertFalse(extractor.extract(body,"values.0","out").isSuccess());
        assertFalse(extractor.extract(body,"pointer:bad","out").isSuccess());
        assertFalse(extractor.extract(body,"pointer:/missing","out").isSuccess());
        assertFalse(extractor.extract("", "data", "out").isSuccess());
    }
}
