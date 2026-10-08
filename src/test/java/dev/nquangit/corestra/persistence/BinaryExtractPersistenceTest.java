package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.session.SessionExtractionRule;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BinaryExtractPersistenceTest {
    @Test
    void binaryExtractorAndDataUrlPrefixRoundTripAlongsideExistingTextExtractor() {
        var binary = new ExtractRule("binary", "Image", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_RAW, "", "imageUrl", List.of("base64Encode", "prefix:data:image/png;base64,"));
        var text = new ExtractRule("text", "Text", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_TEXT, "", "text", List.of());
        var step = new FlowStep("step", "Image", true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/image", List.of(), ""),
                List.of(binary, text), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        var flow = new FlowDefinition("flow", "Image flow", "", true, List.of(step), null, null, null);
        FlowSerializer serializer = new FlowSerializer();

        assertEquals(List.of(flow), serializer.deserialize(serializer.serialize(List.of(flow))));
    }

    @Test
    void oldImplicitEncodingRulesMigrateToRawExtractionAndExplicitBase64Transform() throws Exception {
        var mapper = new ObjectMapper();
        var original = new ExtractRule("image", "Image", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_RAW, "", "imageUrl", List.of("prefix:data:image/png;base64,"));
        var json = mapper.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("extractorType", "BODY_BASE64");
        var migrated = mapper.treeToValue(json, ExtractRule.class);

        assertEquals(ExtractRule.ExtractorType.BODY_RAW, migrated.extractorType());
        assertEquals(List.of("base64Encode", "prefix:data:image/png;base64,"), migrated.transformChain());
        assertFalse(mapper.writeValueAsString(migrated).contains("BODY_BASE64"));
        assertEquals(migrated, mapper.readValue(mapper.writeValueAsString(migrated), ExtractRule.class));

        var session = mapper.convertValue(Map.of("id", "image", "enabled", true, "sourceSide", "RESPONSE",
                "extractorType", "BODY_BASE64", "targetKey", "imageUrl", "transformChain", List.of("prefix:data:image/png;base64,")),
                SessionExtractionRule.class);
        assertEquals(ExtractRule.ExtractorType.BODY_RAW, session.extractorType());
        assertEquals(migrated.transformChain(), session.transformChain());
        assertFalse(mapper.writeValueAsString(session).contains("BODY_BASE64"));
    }
}
