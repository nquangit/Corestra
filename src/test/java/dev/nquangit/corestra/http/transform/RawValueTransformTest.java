package dev.nquangit.corestra.http.transform;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RawValueTransformTest {
    private final ValueTransformService service = new ValueTransformService();

    @Test
    void slicesRawBytesBeforeEncodingAndOnlyThenAddsTextPrefix() {
        byte[] bytes = {0, (byte) 0xff, (byte) 0x80, 1};
        var result = service.applyBytes(bytes, List.of("substring:1:3", "base64Encode", "prefix:data:image/png;base64,"));

        assertTrue(result.success(), result.error());
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{(byte) 0xff, (byte) 0x80}), result.value());
        assertArrayEquals(new byte[]{0, (byte) 0xff, (byte) 0x80, 1}, bytes);
    }

    @Test
    void rawBodyCanBecomeHexWithoutAnyBase64Operation() {
        var result = service.applyBytes(new byte[]{0, (byte) 0xff, (byte) 0x80}, List.of("hexEncode", "uppercase", "prefix:hex:"));

        assertTrue(result.success(), result.error());
        assertEquals("hex:00FF80", result.value());
    }

    @Test
    void explicitlyDecodesUtf8BeforeApplyingTextTransforms() {
        var result = service.applyBytes("  ảnh  ".getBytes(StandardCharsets.UTF_8), List.of("decodeText", "trim", "prefix:Text: "));

        assertTrue(result.success(), result.error());
        assertEquals("Text: ảnh", result.value());
    }

    @Test
    void charsetConversionIsExplicitAndTextBase64StillUsesUtf8() {
        var raw = service.applyBytes(new byte[]{(byte) 0xff}, List.of("base64Encode"));
        var decodedText = service.applyBytes(new byte[]{(byte) 0xff}, List.of("decodeText:ISO-8859-1", "base64Encode"));

        assertEquals("/w==", raw.value());
        assertEquals(Base64.getEncoder().encodeToString("ÿ".getBytes(StandardCharsets.UTF_8)), decodedText.value());
        assertNotEquals(raw.value(), decodedText.value());
    }

    @Test
    void invalidUtf8AndUnknownCharsetFailWithoutReplacingBytes() {
        for (String conversion : List.of("decodeText:UTF-8", "decodeText:not-a-charset")) {
            var result = service.applyBytes(new byte[]{(byte) 0xff}, List.of(conversion));
            assertFalse(result.success());
            assertTrue(result.error().contains("#1"));
            assertEquals("", result.value());
        }
    }

    @Test
    void textOperationsRequireAnExplicitByteConversionFirst() {
        for (String operation : List.of("prefix:data:", "trim", "lowercase", "replaceRegex:a=>b", "base64Decode")) {
            var result = service.applyBytes(new byte[]{(byte) 0xff}, List.of(operation, "base64Encode"));
            assertFalse(result.success());
            assertTrue(result.error().contains("#1"));
            assertTrue(result.error().contains("raw bytes"));
            assertTrue(service.validateBytesChain(List.of(operation, "base64Encode")).isPresent());
        }
    }

    @Test
    void rawResultNeedsExplicitConversionAndEmptyInputCanBeEncoded() {
        for (List<String> transforms : List.of(List.<String>of(), List.of("substring:0:1"))) {
            var result = service.applyBytes(new byte[]{1}, transforms);
            assertFalse(result.success());
            assertTrue(result.error().contains("still raw bytes"));
            assertTrue(service.validateBytesChain(transforms).isPresent());
        }
        var empty = service.applyBytes(new byte[0], List.of("base64Encode"));
        assertTrue(empty.success());
        assertEquals("", empty.value());
    }

    @Test
    void rejectsInvalidByteRangesAndDecodeOfAlreadyConvertedText() {
        var bounds = service.applyBytes(new byte[]{1}, List.of("substring:0:2", "base64Encode"));
        assertFalse(bounds.success());
        assertTrue(bounds.error().contains("#1"));
        var type = service.applyBytes(new byte[]{1}, List.of("base64Encode", "decodeText"));
        assertFalse(type.success());
        assertTrue(type.error().contains("#2"));
        assertTrue(service.validateBytesChain(List.of("base64Encode", "decodeText")).isPresent());
        assertTrue(service.validateBytesChain(List.of("substring:0:1", "base64Encode", "prefix:data:")).isEmpty());
    }
}
