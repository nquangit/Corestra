package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexSelectionTest {
    private static final String REQUEST_HEAD = "GET /users/42?view=full HTTP/1.1\r\nHost: api.test\r\nX-Csrf: abc123\r\n\r\n";
    private static final String REQUEST_BODY = "{\"id\":\"user-42\"}";

    @Test
    void regexInputUsesTheSameTextForEverySource() {
        HttpMessageSnapshot snapshot = new HttpMessageSnapshot(
                "HTTP/1.1 201 Created", List.of("X-Id: 7"), "body");

        assertEquals("body", snapshot.regexInput(ExtractRule.RegexSource.BODY));
        assertEquals("HTTP/1.1 201 Created\r\nX-Id: 7", snapshot.regexInput(ExtractRule.RegexSource.HEADERS));
        assertEquals("HTTP/1.1 201 Created\r\nX-Id: 7\r\n\r\nbody",
                snapshot.regexInput(ExtractRule.RegexSource.FULL_MESSAGE));
    }

    @Test
    void startLineOmitsBlankReasonPhrase() {
        assertEquals("HTTP/2 200", HttpMessageSnapshot.responseStartLine("HTTP/2", 200, ""));
        assertEquals("HTTP/1.1 404 Not Found", HttpMessageSnapshot.responseStartLine("HTTP/1.1", 404, "Not Found"));
        assertEquals("HTTP/1.1 204", HttpMessageSnapshot.responseStartLine(null, 204, null));
    }

    @Test
    void requestLineSelectionMapsToHeadersSource() {
        int start = REQUEST_HEAD.indexOf("42");
        RegexSelection selection = map(ExtractRule.SourceSide.REQUEST, REQUEST_HEAD, REQUEST_BODY, start, start + 2);

        assertEquals(ExtractRule.RegexSource.HEADERS, selection.source());
        assertEquals(ExtractRule.SourceSide.REQUEST, selection.side());
        assertSelected(selection, "42");
        assertEquals("GET /users/42?view=full HTTP/1.1", selection.snapshot().statusLine());
    }

    @Test
    void headerSelectionMapsToHeadersSource() {
        int start = REQUEST_HEAD.indexOf("abc123");
        RegexSelection selection = map(ExtractRule.SourceSide.REQUEST, REQUEST_HEAD, REQUEST_BODY, start, start + 6);

        assertEquals(ExtractRule.RegexSource.HEADERS, selection.source());
        assertSelected(selection, "abc123");
    }

    @Test
    void bodySelectionMapsToBodySource() {
        int start = REQUEST_HEAD.length() + REQUEST_BODY.indexOf("user-42");
        RegexSelection selection = map(ExtractRule.SourceSide.REQUEST, REQUEST_HEAD, REQUEST_BODY, start, start + 7);

        assertEquals(ExtractRule.RegexSource.BODY, selection.source());
        assertSelected(selection, "user-42");
    }

    @Test
    void selectionAcrossHeadersAndBodyMapsToFullMessage() {
        int start = REQUEST_HEAD.indexOf("abc123");
        int end = REQUEST_HEAD.length() + 2;
        RegexSelection selection = map(ExtractRule.SourceSide.RESPONSE, REQUEST_HEAD, REQUEST_BODY, start, end);

        assertEquals(ExtractRule.RegexSource.FULL_MESSAGE, selection.source());
        assertSelected(selection, "abc123\r\n\r\n{\"");
    }

    @Test
    void lfOnlyHeadIsNormalisedLikeRuntimeSnapshots() {
        String head = "HTTP/1.1 200 OK\nX-Token: t-1\n\n";
        int start = head.indexOf("t-1");
        RegexSelection selection = map(ExtractRule.SourceSide.RESPONSE, head, "ok", start, start + 3);

        assertEquals(ExtractRule.RegexSource.HEADERS, selection.source());
        assertSelected(selection, "t-1");
    }

    @Test
    void separatorOnlyOrEmptySelectionIsRejected() {
        int separator = REQUEST_HEAD.length() - 2;
        assertTrue(RegexSelection.fromRawMessage(ExtractRule.SourceSide.REQUEST, REQUEST_HEAD, REQUEST_BODY,
                separator, separator + 1).isEmpty());
        assertTrue(RegexSelection.fromRawMessage(ExtractRule.SourceSide.REQUEST, REQUEST_HEAD, REQUEST_BODY,
                3, 3).isEmpty());
    }

    @Test
    void suggestionFromRequestSelectionExtractsSameValueAtRuntime() {
        int start = REQUEST_HEAD.indexOf("42");
        RegexSelection selection = map(ExtractRule.SourceSide.REQUEST, REQUEST_HEAD, REQUEST_BODY, start, start + 2);
        RegexSuggestionService.SuggestionResult suggestion = new RegexSuggestionService().suggest(
                selection.snapshot(), selection.source(), selection.start(), selection.end());
        assertTrue(suggestion.success(), suggestion.failureReason());

        ExtractRule rule = new ExtractRule("r", "user id", true, selection.side(), ExtractRule.ExtractorType.REGEX,
                suggestion.suggestion().regex(), "userId", List.of(), selection.source(),
                suggestion.suggestion().captureGroup());
        ExtractionResult result = new ExtractionService().extract(rule, selection.snapshot(), null);

        assertTrue(result.isSuccess(), result.failureReason());
        assertEquals("42", result.value());
    }

    private static RegexSelection map(ExtractRule.SourceSide side, String head, String body, int start, int end) {
        Optional<RegexSelection> selection = RegexSelection.fromRawMessage(side, head, body, start, end);
        assertTrue(selection.isPresent(), "selection should map");
        return selection.orElseThrow();
    }

    private static void assertSelected(RegexSelection selection, String expected) {
        String text = selection.snapshot().regexInput(selection.source());
        assertEquals(expected, text.substring(selection.start(), selection.end()));
    }
}
