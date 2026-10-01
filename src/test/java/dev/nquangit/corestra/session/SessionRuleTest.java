package dev.nquangit.corestra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionRuleTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void propagatesTransformsAndRegexSettingsToRuntimeRules() {
        SessionExtractionRule extraction = new SessionExtractionRule(
                "extract", true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.REGEX,
                "X-Token: (\\S+)", "token", List.of("PROXY"),
                List.of("trim", "substring:0:8"), ExtractRule.RegexSource.HEADERS, 1);
        SessionInjectionRule injection = new SessionInjectionRule(
                "inject", true, InjectRule.Target.REQUEST_HEADER, "Authorization",
                "{{session.token}}", InjectRule.WriteStrategy.OVERWRITE, List.of("SCANNER"),
                List.of("prefix:Bearer "));

        ExtractRule runtimeExtraction = extraction.toExtractRule();
        InjectRule runtimeInjection = injection.toInjectRule("abc123");

        assertEquals(ExtractRule.RegexSource.HEADERS, runtimeExtraction.regexSource());
        assertEquals(1, runtimeExtraction.regexGroupIndex());
        assertEquals(List.of("trim", "substring:0:8"), runtimeExtraction.transformChain());
        assertEquals(List.of("prefix:Bearer "), runtimeInjection.transformChain());
    }

    @Test
    void legacyJsonDefaultsToBodyRegexAndEmptyTransforms() throws Exception {
        String extractionJson = """
                {"id":"extract","enabled":true,"sourceSide":"RESPONSE","extractorType":"REGEX",
                 "sourceSelector":"token=(\\\\w+)|1","targetKey":"token","allowedToolTypes":[]}
                """;
        String injectionJson = """
                {"id":"inject","enabled":true,"target":"REQUEST_HEADER","targetSelector":"Authorization",
                 "valueTemplate":"{{session.token}}","writeStrategy":"OVERWRITE","allowedToolTypes":[]}
                """;

        SessionExtractionRule extraction = OBJECT_MAPPER.readValue(extractionJson, SessionExtractionRule.class);
        SessionInjectionRule injection = OBJECT_MAPPER.readValue(injectionJson, SessionInjectionRule.class);

        assertEquals(ExtractRule.RegexSource.BODY, extraction.regexSource());
        assertNull(extraction.regexGroupIndex());
        assertEquals(List.of(), extraction.transformChain());
        assertEquals(List.of(), injection.transformChain());
    }
}
