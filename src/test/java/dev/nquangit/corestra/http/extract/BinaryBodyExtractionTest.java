package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BinaryBodyExtractionTest {
    private final ExtractionService service = new ExtractionService();

    @Test
    void preservesAllByteValuesAndExcludesHeadersDespiteLossyBodyText() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        HttpMessageSnapshot response = new HttpMessageSnapshot("HTTP/1.1 200 OK",
                List.of("Content-Type: image/png"), "\uFFFD", bytes);

        ExtractionResult result = service.extract(rule(ExtractRule.SourceSide.RESPONSE, List.of("base64Encode")), null, response);

        assertTrue(result.isSuccess());
        assertArrayEquals(bytes, Base64.getDecoder().decode(result.value()));
        assertFalse(result.value().contains("\n"));
    }

    @Test
    void addsDataUrlPrefixOnceAfterBinaryEncoding() {
        byte[] bytes = {(byte) 0xff, 0, (byte) 0x80};
        var result = service.extract(rule(ExtractRule.SourceSide.RESPONSE,
                List.of("base64Encode", "prefix:data:image/jpeg;base64,")), null,
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "corrupted", bytes));

        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes), result.value());
    }

    @Test
    void missingRawBytesFailsInsteadOfReencodingText() {
        var result = service.extract(rule(ExtractRule.SourceSide.RESPONSE, List.of("base64Encode")), null,
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "not exact bytes"));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReason().contains("Raw body bytes are unavailable"));
    }

    @Test
    void emptyBodyIsAvailableAndProducesEmptyBase64() {
        var result = service.extract(rule(ExtractRule.SourceSide.RESPONSE, List.of("base64Encode")), null,
                new HttpMessageSnapshot("HTTP/1.1 204 No Content", List.of(), "", new byte[0]));

        assertTrue(result.isSuccess());
        assertEquals("", result.value());
    }

    @Test
    void requestRuleUsesRequestBytesRatherThanResponseBytes() {
        var result = service.extract(rule(ExtractRule.SourceSide.REQUEST, List.of("base64Encode")),
                new HttpMessageSnapshot("POST / HTTP/1.1", List.of(), "", new byte[]{1, 2}),
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "", new byte[]{3}));

        assertArrayEquals(new byte[]{1, 2}, Base64.getDecoder().decode(result.value()));
    }

    @Test
    void snapshotCopiesInputAndOutputArraysAndComparesContents() {
        byte[] input = {1, 2, 3};
        var snapshot = new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "", input);
        input[0] = 9;
        byte[] output = snapshot.bodyBytes();
        output[1] = 9;
        var same = new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "", new byte[]{1, 2, 3});

        assertArrayEquals(new byte[]{1, 2, 3}, snapshot.bodyBytes());
        assertEquals(same, snapshot);
        assertEquals(same.hashCode(), snapshot.hashCode());
    }

    @Test
    void rawExtractorDoesNotPerformEncodingWithoutAnExplicitTransform() {
        var snapshot = new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "", new byte[]{(byte) 0xff});
        var unconverted = service.extract(rule(ExtractRule.SourceSide.RESPONSE, List.of()), null, snapshot);
        var hex = service.extract(rule(ExtractRule.SourceSide.RESPONSE, List.of("hexEncode")), null, snapshot);

        assertFalse(unconverted.isSuccess());
        assertTrue(unconverted.failureReason().contains("still raw bytes"));
        assertEquals("ff", hex.value());
    }

    private static ExtractRule rule(ExtractRule.SourceSide side, List<String> transforms) {
        return new ExtractRule("binary", "Binary body", true, side,
                ExtractRule.ExtractorType.BODY_RAW, "", "image", transforms);
    }
}
