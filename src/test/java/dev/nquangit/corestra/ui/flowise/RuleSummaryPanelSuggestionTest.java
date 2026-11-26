package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSummaryPanelSuggestionTest {

    @Test
    void nameAndValueSuggestionsForTheSameSlotAreMergedOnce() {
        String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
                + "{\"csrf\":\"Tok3nValue1234\",\"session\":\"S3ssionValue99xx\"}";
        ExtractRule csrf = new ExtractRule("x1", "csrf", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.JSON_PATH, "csrf", "csrf", List.of());
        FlowStep first = new FlowStep("s1", "Load form", true, "",
                new StoredRequestTemplate("GET", "https", "app.test", 443, "/form", List.of(), ""),
                List.of(csrf), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW,
                Base64.getEncoder().encodeToString(response.getBytes(StandardCharsets.UTF_8)));
        FlowStep second = new FlowStep("s2", "Submit", true, "",
                new StoredRequestTemplate("POST", "https", "app.test", 443, "/submit?csrf=Tok3nValue1234",
                        List.of("X-Session: S3ssionValue99xx"), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        FlowDefinition flow = new FlowDefinition("f", "F", "", true, List.of(first, second), null, null, null);

        List<RuleSummaryPanel.SuggestionEntry> entries = RuleSummaryPanel.suggestionEntries(flow, second);

        assertEquals(2, entries.size(), entries.toString());
        assertNotNull(entries.getFirst().byName(), "name match listed first");
        assertEquals("csrf", entries.getFirst().byName().rule().targetSelector());
        assertNotNull(entries.get(1).byValue());
        assertEquals("X-Session", entries.get(1).byValue().injectRule().targetSelector());
        assertTrue(entries.stream().allMatch(RuleSummaryPanel.SuggestionEntry::preselected));
    }
}
