package dev.nquangit.corestra.fixtures;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end style extraction tests against realistic fintech response fixtures loaded from
 * {@code src/test/resources/fixtures}. Exercises the real {@link ExtractionService} on the kind of
 * JSON bodies a tester captures from a corporate-banking API.
 */
class FintechFlowFixtureTest {

    private final ExtractionService extractionService = new ExtractionService();

    @Test
    void extractsAccessTokenFromLoginResponseViaJsonPath() throws IOException {
        HttpMessageSnapshot login = response("login-response.json");

        ExtractionResult result = extractionService.extract(
                jsonRule("data.accessToken", "accessToken"), null, login);

        assertTrue(result.isSuccess(), () -> "extraction failed: " + result.failureReason());
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.fintech.signature", result.value());
    }

    @Test
    void extractsNestedCustomerIdFromLoginResponse() throws IOException {
        HttpMessageSnapshot login = response("login-response.json");

        ExtractionResult result = extractionService.extract(
                jsonRule("data.customer.id", "customerId"), null, login);

        assertTrue(result.isSuccess(), () -> "extraction failed: " + result.failureReason());
        assertEquals("CUST-00417", result.value());
    }

    @Test
    void extractsPaginationCursorFromAccountsResponse() throws IOException {
        HttpMessageSnapshot accounts = response("accounts-response.json");

        ExtractionResult result = extractionService.extract(
                jsonRule("nextCursor", "cursor"), null, accounts);

        assertTrue(result.isSuccess(), () -> "extraction failed: " + result.failureReason());
        assertEquals("cursor_2c9a", result.value());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ExtractRule jsonRule(String selector, String targetVar) {
        return new ExtractRule(
                "rule-" + targetVar, targetVar, true,
                SourceSide.RESPONSE, ExtractorType.JSON_PATH, selector, targetVar, List.of());
    }

    private static HttpMessageSnapshot response(String fixtureName) throws IOException {
        return new HttpMessageSnapshot(
                "HTTP/1.1 200 OK",
                List.of("Content-Type: application/json"),
                fixture(fixtureName));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = FintechFlowFixtureTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
