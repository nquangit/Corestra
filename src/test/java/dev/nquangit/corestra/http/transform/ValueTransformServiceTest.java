package dev.nquangit.corestra.http.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTransformServiceTest {
    private final ValueTransformService service = new ValueTransformService();

    @Test
    void supportsCommonTextEncodingAndSlicingTransforms() {
        ValueTransformResult result = service.apply("  hello world  ", List.of(
                "trim", "uppercase", "substring:0:5", "suffix:-OK", "base64Encode", "base64Decode"));

        assertTrue(result.success());
        assertEquals("HELLO-OK", result.value());
    }

    @Test
    void replaceRegexSupportsCaptureGroups() {
        ValueTransformResult result = service.apply("user-123", List.of("replaceRegex:user-(\\d+)=>id:$1"));

        assertTrue(result.success());
        assertEquals("id:123", result.value());
    }

    @Test
    void unknownTransformFailsWithItsPositionAndName() {
        ValueTransformResult result = service.apply("value", List.of("trim", "explode"));

        assertFalse(result.success());
        assertTrue(result.error().contains("#2"));
        assertTrue(result.error().contains("explode"));
    }
}
