package dev.nquangit.corestra.watch;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchDefinitionValidatorTest {

    private static boolean mentions(List<String> messages, String fragment) {
        return messages.stream().anyMatch(message -> message.contains(fragment));
    }

    @Test
    void fullFixtureIsValid() {
        WatchDefinitionValidator.Result result = WatchDefinitionValidator.validate(WatchConfigFixtures.fullWatch("w"));
        assertEquals(List.of(), result.errors());
        assertTrue(result.valid());
    }

    @Test
    void reportsScopeRegexAndMethodErrors() {
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w")
                .withScope(new WatchScope(List.of("PO ST"), "([a", "(b"));
        List<String> errors = WatchDefinitionValidator.validate(watch).errors();
        assertTrue(mentions(errors, "Host regex"), errors.toString());
        assertTrue(mentions(errors, "Path regex"), errors.toString());
        assertTrue(mentions(errors, "method"), errors.toString());
    }

    @Test
    void enabledWatchNeedsProbeAndObservation() {
        List<String> errors = WatchDefinitionValidator.validate(WatchDefinition.empty("w", "W").withEnabled(true)).errors();
        assertTrue(mentions(errors, "probe"), errors.toString());
        assertTrue(mentions(errors, "observation"), errors.toString());
        assertTrue(WatchDefinitionValidator.validate(WatchDefinition.empty("w", "W")).valid(),
                "a disabled draft may be incomplete");
    }

    @Test
    void validatesObservationSelectors() {
        WatchNumberFormat badStrip = new WatchNumberFormat(false, ".", ",", 0, "[");
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w").withObservations(List.of(
                new WatchObservation("1", "a", WatchObservation.Source.JSON_SELECTOR, "$..[", "", "", "", null, null),
                new WatchObservation("2", "b", WatchObservation.Source.JSON_COLLECTION, "", "", "id", "", null, null),
                new WatchObservation("3", "c", WatchObservation.Source.REGEX_COLLECTION, "(\\d+)=(\\d+)", "", "", "", null, null),
                new WatchObservation("4", "a", WatchObservation.Source.REGEX, "x(", "", "", "", null, badStrip),
                new WatchObservation("5", "d[0]", WatchObservation.Source.REGEX, "v=(\\d+)", "", "", "", null, null)));
        List<String> errors = WatchDefinitionValidator.validate(watch).errors();
        assertTrue(mentions(errors, "Invalid JSON selector"), errors.toString());
        assertTrue(mentions(errors, "items path"), errors.toString());
        assertTrue(mentions(errors, "value path"), errors.toString());
        assertTrue(mentions(errors, "named groups"), errors.toString());
        assertTrue(mentions(errors, "Duplicate observation name"), errors.toString());
        assertTrue(mentions(errors, "Strip regex"), errors.toString());
        assertTrue(mentions(errors, "brackets"), errors.toString());
    }

    @Test
    void validatesRequestVariables() {
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w").withRequestVariables(List.of(
                new ExtractRule("1", "x", true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.HEADER,
                        "X-A", "x", List.of()),
                new ExtractRule("2", "bad name", true, ExtractRule.SourceSide.REQUEST, ExtractRule.ExtractorType.REGEX,
                        "a(", "bad name", List.of()),
                new ExtractRule("3", "j", true, ExtractRule.SourceSide.REQUEST, ExtractRule.ExtractorType.JSON_PATH,
                        "$..[", "x", List.of())));
        List<String> errors = WatchDefinitionValidator.validate(watch).errors();
        assertTrue(mentions(errors, "REQUEST side"), errors.toString());
        assertTrue(mentions(errors, "variable name"), errors.toString());
        assertTrue(mentions(errors, "regex"), errors.toString());
        assertTrue(mentions(errors, "Invalid JSON selector"), errors.toString());
        assertTrue(mentions(errors, "Duplicate request variable"), errors.toString());
    }

    @Test
    void validatesExpectations() {
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w").withExpectations(List.of(
                new WatchExpectation("1", true, "", WatchExpectation.Type.CHANGED, "", "", ""),
                new WatchExpectation("2", true, "balance[*]", WatchExpectation.Type.DELTA_EQUALS, "", "", ""),
                new WatchExpectation("3", true, "balance[*]", WatchExpectation.Type.DELTA_BETWEEN, "-(1", "", ""),
                new WatchExpectation("4", true, "balance[*]", WatchExpectation.Type.DELTA_EQUALS, "-({{amount} + 1", "", "x"),
                new WatchExpectation("5", true, "balance[*]", WatchExpectation.Type.DELTA_EQUALS,
                        "-{{amount}} - max({{amount}} * 0.011, 5000) + {{unknown}}", "", "0.5")));
        WatchDefinitionValidator.Result result = WatchDefinitionValidator.validate(watch);
        List<String> errors = result.errors();
        assertTrue(mentions(errors, "key pattern"), errors.toString());
        assertTrue(mentions(errors, "needs an expression"), errors.toString());
        assertTrue(mentions(errors, "upper bound"), errors.toString());
        assertTrue(mentions(errors, "parenthes"), errors.toString());
        assertTrue(mentions(errors, "{{"), errors.toString());
        assertTrue(mentions(errors, "Tolerance"), errors.toString());
        assertEquals(6, errors.size(), errors.toString());
        assertTrue(mentions(result.warnings(), "unknown"), result.warnings().toString());
    }

    @Test
    void disabledExpectationsAreNotChecked() {
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w").withExpectations(List.of(
                new WatchExpectation("1", false, "", WatchExpectation.Type.DELTA_EQUALS, "((", "", "x")));
        assertTrue(WatchDefinitionValidator.validate(watch).valid());
    }
}
