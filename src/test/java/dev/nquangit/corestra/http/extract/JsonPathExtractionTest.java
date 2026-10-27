package dev.nquangit.corestra.http.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.JsonResultMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathExtractionTest {
    private static final String BODY = """
            {"store":{"book":[
              {"id":10,"title":"JavaScript Essentials","price":10,"active":true},
              {"id":20,"title":"javascript Advanced","price":20,"active":false},
              {"id":30,"title":"Java","price":0,"active":true}]},
             "odd.key":{"a/b":"special"},"nil":null,"zero":0,"flag":false,"blank":"","$token":"legacy"}
            """;
    private final JsonBodyExtractor extractor = new JsonBodyExtractor();

    @Test void regexFilterMatchesCaseInsensitivelyWithExplicitResultSelection() throws Exception {
        String query = "$.store.book[?(@.title =~ /.*JavaScript.*/i)]";
        var ambiguous = extractor.extract(BODY, query, "out");
        assertFalse(ambiguous.isSuccess()); assertTrue(ambiguous.failureReason().contains("2 values"));
        var first = extractor.extract(BODY, query, "out", JsonResultMode.FIRST);
        assertTrue(first.isSuccess(), first.failureReason());
        assertEquals(10, new ObjectMapper().readTree(first.value()).path("id").asInt());
        var all = extractor.extract(BODY, query + ".title", "out", JsonResultMode.ALL);
        assertEquals("[\"JavaScript Essentials\",\"javascript Advanced\"]", all.value());
        assertEquals("javascript Advanced", extractor.extract(BODY, "$.store.book[?(@.id == 20)].title", "out").value());
    }

    @Test void supportsBracketsRecursiveSearchWildcardsSlicesAndLogicalFilters() {
        assertEquals("special", extractor.extract(BODY, "$['odd.key']['a/b']", "out").value());
        assertEquals("Java", extractor.extract(BODY, "$.store.book[2].title", "out").value());
        assertEquals("[10,20,30]", extractor.extract(BODY, "$..id", "out", JsonResultMode.ALL).value());
        assertEquals("[10,20,30]", extractor.extract(BODY, "$.store.book[*].id", "out", JsonResultMode.ALL).value());
        assertEquals("[10,20]", extractor.extract(BODY, "$.store.book[0:2].id", "out", JsonResultMode.ALL).value());
        assertEquals("[10,30]", extractor.extract(BODY, "$.store.book[0,2].id", "out", JsonResultMode.ALL).value());
        assertEquals("10", extractor.extract(BODY, "$.store.book[?(@.price >= 10 && @.active == true)].id", "out").value());
        assertEquals("20", extractor.extract(BODY, "$.store.book[?(@.price > 10 || @.id == 20)].id", "out").value());
    }

    @Test void definiteArrayNodesAreSingleValuesAndAllModeAlwaysWrapsMatches() {
        assertEquals("[1,2]", extractor.extract("{\"items\":[1,2]}", "$.items", "out").value());
        assertEquals("[[1,2]]", extractor.extract("{\"items\":[1,2]}", "$.items", "out", JsonResultMode.ALL).value());
        assertEquals("[1]", extractor.extract("{\"id\":1}", "$.id", "out", JsonResultMode.ALL).value());
        assertEquals("[]", extractor.extract("[]", "$", "out").value());
        assertEquals("root-array", extractor.extract("[{\"name\":\"root-array\"}]", "$[0].name", "out").value());
    }

    @Test void scalarValuesRemainTextAndFunctionsWorkWithoutASecondJsonParser() {
        assertEquals("0", extractor.extract(BODY, "$.zero", "out").value());
        assertEquals("false", extractor.extract(BODY, "$.flag", "out").value());
        assertEquals("", extractor.extract(BODY, "$.blank", "out").value());
        assertEquals("3", extractor.extract(BODY, "$.store.book.length()", "out").value());
    }

    @Test void emptyMissingAndNullMatchesNeverProduceInventedValues() {
        for (JsonResultMode mode : JsonResultMode.values()) {
            assertFalse(extractor.extract(BODY, "$.store.book[?(@.id == 999)]", "out", mode).isSuccess());
            assertFalse(extractor.extract(BODY, "$.missing", "out", mode).isSuccess());
            assertFalse(extractor.extract(BODY, "$.nil", "out", mode).isSuccess());
        }
        assertFalse(extractor.extract("[null,2]", "$[*]", "out", JsonResultMode.FIRST).isSuccess());
        assertEquals("[null,2]", extractor.extract("[null,2]", "$[*]", "out", JsonResultMode.ALL).value());
    }

    @Test void invalidSyntaxRegexAndJsonAreReportedAsFailures() {
        for (String selector : List.of("$.", "$.store.book[?(@.title =~ /(/)]", "pointer:bad")) {
            assertTrue(JsonBodyExtractor.validateSelector(selector).isPresent(), selector);
            assertFalse(extractor.extract(BODY, selector, "out").isSuccess());
        }
        assertFalse(extractor.extract("not-json", "$.id", "out").isSuccess());
    }

    @Test void legacySelectorsAndJsonPointersKeepTheirExactBehavior() {
        assertEquals("0", extractor.extract(BODY, "zero", "out").value());
        assertEquals("", extractor.extract(BODY, "store", "out").value(), "Legacy container conversion is unchanged");
        assertEquals("legacy", extractor.extract(BODY, "$token", "out").value());
        assertEquals("Java", extractor.extract(BODY, "pointer:/store/book/2/title", "out", JsonResultMode.ALL).value());
        assertEquals("special", extractor.extract(BODY, "pointer:/odd.key/a~1b", "out").value());
        assertFalse(JsonBodyExtractor.isJsonPath("store.book"));
        assertFalse(JsonBodyExtractor.isJsonPath("$token"));
    }

    @Test void extractionServiceAppliesTransformsAfterTheSelectedJsonResult() {
        var rule = new ExtractRule("r", "Titles", true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.JSON_PATH,
                "$.store.book[*].id", "ids", List.of("prefix:ids=")).withJsonResultMode(JsonResultMode.ALL);
        var result = new ExtractionService().extract(rule, null, new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), BODY));
        assertEquals("ids=[10,20,30]", result.value());
    }
}
