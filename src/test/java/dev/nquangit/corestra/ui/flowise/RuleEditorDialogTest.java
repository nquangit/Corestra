package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.util.List;
import java.util.Arrays;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuleEditorDialogTest {
    private static final StoredRequestTemplate TEMPLATE = new StoredRequestTemplate(
            "GET", "https", "example.test", 443, "/", List.of(), "");

    @Test
    void editorsOfferRawBodyWithoutTheLegacyPurposeSpecificExtractor() {
        var options = Arrays.stream(ExtractRule.ExtractorType.selectableValues()).map(Enum::name).toList();
        assertTrue(options.contains("BODY_RAW"));
        assertTrue(options.contains("BODY_TEXT"));
        assertFalse(options.contains("BODY_BASE64"));
    }

    @Test
    void binaryPreviewKeepsSavedResponseBytesAndMatchesRuntimeExtraction() {
        byte[] body = new byte[256];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        for (String separator : List.of("\r\n", "\n")) {
            byte[] head = ("HTTP/1.1 200 OK" + separator + "Content-Type: image/png"
                    + separator + separator).getBytes(StandardCharsets.US_ASCII);
            byte[] raw = Arrays.copyOf(head, head.length + body.length);
            System.arraycopy(body, 0, raw, head.length, body.length);
            FlowStep step = step("image", "Image", true, List.of())
                    .withLastResponseRaw(Base64.getEncoder().encodeToString(raw));
            ExtractRule rule = new ExtractRule("image", "Image", true, ExtractRule.SourceSide.RESPONSE,
                    ExtractRule.ExtractorType.BODY_RAW, "", "imageUrl", List.of("base64Encode", "prefix:data:image/png;base64,"));

            var result = RuleEditorDialog.testExtraction(step, rule);

            assertTrue(result.isSuccess(), result.failureReason());
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(body), result.value());
            assertArrayEquals(body, Base64.getDecoder().decode(result.value().split(",", 2)[1]));
        }
    }

    @Test
    void previewReportsUnavailableResponseInsteadOfEncodingMalformedSavedData() {
        ExtractRule rule = new ExtractRule("image", "Image", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_RAW, "", "imageUrl", List.of());
        for (String raw : List.of("", "!invalid-base64", Base64.getEncoder().encodeToString(
                "HTTP/1.1 200 OK".getBytes(StandardCharsets.US_ASCII)))) {
            var result = RuleEditorDialog.testExtraction(step("image", "Image", true, List.of()).withLastResponseRaw(raw), rule);
            assertFalse(result.isSuccess());
            assertEquals("No response message available", result.failureReason());
        }
    }

    @Test
    void injectionChoicesContainOnlyEnabledExtractorsFromEarlierEnabledSteps() {
        FlowStep first = step("first", "Login", true, List.of(
                extract("token-old", "Old token", true, "token"),
                extract("disabled", "Disabled", false, "ignored")));
        FlowStep skipped = step("skipped", "Disabled step", false, List.of(
                extract("skipped-token", "Skipped token", true, "ignoredStep")));
        FlowStep second = step("second", "Refresh", true, List.of(
                extract("token-new", "Fresh token", true, "token"),
                extract("csrf", "CSRF", true, "csrf")));
        FlowStep current = step("current", "Target", true, List.of(
                extract("current-value", "Current", true, "tooLate")));
        FlowStep later = step("later", "Later", true, List.of(
                extract("later-value", "Later", true, "future")));
        FlowDefinition flow = new FlowDefinition(
                "flow", "Flow", "", true, List.of(first, skipped, second, current, later),
                FlowRuleSet.empty(), AggregationPlan.disabled(), FlowTriggerConfig.none());

        List<RuleEditorDialog.ExtractedVariableOption> options =
                RuleEditorDialog.availableExtractedVariables(flow, current);

        assertEquals(List.of("token", "csrf"), options.stream().map(
                RuleEditorDialog.ExtractedVariableOption::key).toList());
        assertEquals("Refresh", options.get(0).stepName());
        assertEquals("Fresh token", options.get(0).ruleName());
        assertEquals(List.of(), RuleEditorDialog.availableExtractedVariables(
                flow, step("not-in-flow", "Stale", true, List.of())));
    }

    @Test
    void selectedVariableStoresTheKeyInsteadOfItsSourceDescription() {
        RuleEditorDialog.ExtractedVariableOption option =
                new RuleEditorDialog.ExtractedVariableOption("token", "Login", "Extract token");
        JComboBox<RuleEditorDialog.ExtractedVariableOption> combo = new JComboBox<>(
                new RuleEditorDialog.ExtractedVariableOption[]{option});
        combo.setEditable(true);
        combo.setSelectedItem(option);

        assertEquals("token", RuleEditorDialog.selectedVariableKey(combo));
        assertEquals("token  —  Login / Extract token", option.displayLabel());
    }

    private static FlowStep step(
            String id,
            String name,
            boolean enabled,
            List<ExtractRule> extractRules) {
        return new FlowStep(
                id,
                name,
                enabled,
                "",
                TEMPLATE,
                extractRules,
                List.of(),
                List.of(),
                null);
    }

    private static ExtractRule extract(
            String id,
            String name,
            boolean enabled,
            String variable) {
        return new ExtractRule(
                id,
                name,
                enabled,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER,
                "X-Value",
                variable,
                List.of());
    }
}
