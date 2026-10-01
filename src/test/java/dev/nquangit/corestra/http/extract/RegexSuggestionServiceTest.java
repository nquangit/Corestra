package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexSuggestionServiceTest {
    private final RegexSuggestionService service = new RegexSuggestionService();

    @Test
    void suggestsUniqueBodyRegexWithCapturedSelection() {
        String body = "{\"access_token\":\"abc-123_X\",\"type\":\"Bearer\"}";
        String selected = "abc-123_X";
        int start = body.indexOf(selected);

        RegexSuggestionService.SuggestionResult result = service.suggest(
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of("Content-Type: application/json"), body),
                ExtractRule.RegexSource.BODY,
                start,
                start + selected.length());

        assertTrue(result.success());
        assertEquals(1, result.suggestion().captureGroup());
        assertTrue(result.suggestion().unique());
        Matcher matcher = Pattern.compile(result.suggestion().regex()).matcher(body);
        assertTrue(matcher.find());
        assertEquals(selected, matcher.group(1));
    }

    @Test
    void supportsHeaderSelections() {
        String header = "X-Csrf-Token: token-9988";
        String selected = "token-9988";
        int start = header.indexOf(selected);

        RegexSuggestionService.SuggestionResult result = service.suggest(
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(header, "Server: test"), "body"),
                ExtractRule.RegexSource.HEADERS,
                start,
                start + selected.length());

        assertTrue(result.success());
        assertEquals(ExtractRule.RegexSource.HEADERS, result.suggestion().source());
        assertEquals(selected, result.suggestion().selectedValue());
    }

    @Test
    void rejectsInvalidOrEmptySelection() {
        RegexSuggestionService.SuggestionResult result = service.suggest(
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "body"),
                ExtractRule.RegexSource.BODY,
                2,
                2);

        assertFalse(result.success());
    }
}
