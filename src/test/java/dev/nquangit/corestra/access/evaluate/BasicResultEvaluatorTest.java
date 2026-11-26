package dev.nquangit.corestra.access.evaluate;

import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.result.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasicResultEvaluatorTest {
    private final BasicResultEvaluator evaluator = new BasicResultEvaluator();

    @Test
    void denyExpectedEvaluatesDeniedAndAllowedStatuses() {
        assertEquals(Verdict.OK, evaluator.evaluate(ExpectedResult.DENY, 403).verdict());
        assertEquals(Verdict.SUSPICIOUS, evaluator.evaluate(ExpectedResult.DENY, 200).verdict());
    }

    @Test
    void allowExpectedEvaluatesAllowedAndDeniedStatuses() {
        assertEquals(Verdict.OK, evaluator.evaluate(ExpectedResult.ALLOW, 200).verdict());
        assertEquals(Verdict.SUSPICIOUS, evaluator.evaluate(ExpectedResult.ALLOW, 403).verdict());
    }

    @Test
    void redirectsAndServerErrorsUseReviewAndErrorVerdicts() {
        assertEquals(Verdict.REVIEW, evaluator.evaluate(ExpectedResult.ALLOW, 302).verdict());
        assertEquals(Verdict.ERROR, evaluator.evaluate(ExpectedResult.DENY, 500).verdict());
    }

    @Test
    void nullProfileKeepsStatusOnlyBehavior() {
        EvaluationResult result = evaluator.evaluate(
                ExpectedResult.DENY,
                200,
                raw(200, "{\"errorCode\":\"ACCESS_DENIED\"}"),
                null);

        assertEquals(Verdict.SUSPICIOUS, result.verdict());
    }

    @Test
    void denyIndicatorCanTreatHttp200BusinessErrorAsDenyOk() {
        EvaluationProfile profile = profile(
                List.of(),
                List.of(indicator(IndicatorType.JSON_PATH_EQUALS, "errorCode", "ACCESS_DENIED")),
                List.of(),
                List.of());

        EvaluationResult result = evaluator.evaluate(
                ExpectedResult.DENY,
                200,
                raw(200, "{\"errorCode\":\"ACCESS_DENIED\"}"),
                profile);

        assertEquals(Verdict.OK, result.verdict());
    }

    @Test
    void allowIndicatorCanTreatHttp200WrapperSuccessAsAllowOk() {
        EvaluationProfile profile = profile(
                List.of(indicator(IndicatorType.JSON_PATH_EQUALS, "success", "true")),
                List.of(),
                List.of(),
                List.of());

        EvaluationResult result = evaluator.evaluate(
                ExpectedResult.ALLOW,
                200,
                raw(200, "{\"success\":true,\"data\":{\"id\":\"acct-1\"}}"),
                profile);

        assertEquals(Verdict.OK, result.verdict());
    }

    @Test
    void denyIndicatorsTakePrecedenceOverAllowIndicators() {
        EvaluationProfile profile = profile(
                List.of(indicator(IndicatorType.JSON_PATH_EQUALS, "success", "true")),
                List.of(indicator(IndicatorType.JSON_PATH_EQUALS, "errorCode", "ACCESS_DENIED")),
                List.of(),
                List.of());

        EvaluationResult result = evaluator.evaluate(
                ExpectedResult.ALLOW,
                200,
                raw(200, "{\"success\":true,\"errorCode\":\"ACCESS_DENIED\"}"),
                profile);

        assertEquals(Verdict.SUSPICIOUS, result.verdict());
    }

    @Test
    void maskedExpectedResultDetectsSensitiveLeakageAndAcceptsMaskedOrAbsentFields() {
        EvaluationProfile profile = profile(
                List.of(indicator(IndicatorType.JSON_PATH_EQUALS, "success", "true")),
                List.of(),
                List.of(new SensitiveFieldRule("sensitive-1", "SSN", true, "user.ssn")),
                List.of(new MaskingRule("mask-1", "Masked SSN", true, "user.ssn", "\\*+")));

        assertEquals(Verdict.SUSPICIOUS, evaluator.evaluate(
                ExpectedResult.MASKED,
                200,
                raw(200, "{\"success\":true,\"user\":{\"ssn\":\"123-45-6789\"}}"),
                profile).verdict());
        assertEquals(Verdict.OK, evaluator.evaluate(
                ExpectedResult.MASKED,
                200,
                raw(200, "{\"success\":true,\"user\":{\"ssn\":\"******\"}}"),
                profile).verdict());
        assertEquals(Verdict.OK, evaluator.evaluate(
                ExpectedResult.MASKED,
                200,
                raw(200, "{\"success\":true,\"user\":{}}"),
                profile).verdict());
    }

    @Test
    void sensitiveFieldInsideJsonArrayIsDetectedAsLeak() {
        EvaluationProfile profile = profile(
                List.of(),
                List.of(),
                List.of(new SensitiveFieldRule("sensitive-1", "SSN", true, "ssn")),
                List.of(new MaskingRule("mask-1", "Masked SSN", true, "ssn", "\\*+")));

        assertEquals(Verdict.SUSPICIOUS, evaluator.evaluate(
                ExpectedResult.MASKED, 200, raw(200, "[{\"ssn\":\"******\"},{\"ssn\":\"123-45-6789\"}]"), profile).verdict());
        assertEquals(Verdict.OK, evaluator.evaluate(
                ExpectedResult.MASKED, 200, raw(200, "[{\"ssn\":\"******\"},{\"ssn\":\"***\"}]"), profile).verdict());
    }

    @Test
    void jsonPathsSupportIndexesWildcardsAndDollarPrefix() {
        String response = raw(200, "{\"data\":{\"items\":[{\"id\":\"a\",\"owner\":\"u1\"},{\"id\":\"b\",\"owner\":\"u2\"}]}}");

        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_EQUALS, "data.items[1].id", "b"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_EQUALS, "$.data.items[*].owner", "u2"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_EQUALS, "data.items.0.id", "a"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_EXISTS, "data.items.owner", ""), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_NOT_EXISTS, "data.items[5].id", ""), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_EXISTS, "$[0].id", ""),
                raw(200, "[{\"id\":1}]"));
    }

    @Test
    void bodyHeaderStatusAndJsonExistenceIndicatorsAreSupported() {
        String response = "HTTP/1.1 200 OK\nX-Result: wrapped-success\n\n{\"success\":true,\"data\":{\"id\":\"acct-1\"}}";

        assertAllowIndicatorMatches(indicator(IndicatorType.STATUS_CODE_IN, "", "200-204"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.HEADER_EXISTS, "X-Result", ""), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.HEADER_VALUE_CONTAINS, "X-Result", "wrapped"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.BODY_CONTAINS, "", "success"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.BODY_NOT_CONTAINS, "", "password"), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_EXISTS, "data.id", ""), response);
        assertAllowIndicatorMatches(indicator(IndicatorType.JSON_PATH_NOT_EXISTS, "data.password", ""), response);
    }

    private static EvaluationProfile profile(
            List<ResponseIndicator> allowIndicators,
            List<ResponseIndicator> denyIndicators,
            List<SensitiveFieldRule> sensitiveFieldRules,
            List<MaskingRule> maskingRules) {
        return new EvaluationProfile(
                "profile-1",
                "Profile",
                true,
                allowIndicators,
                denyIndicators,
                sensitiveFieldRules,
                maskingRules);
    }

    private static ResponseIndicator indicator(IndicatorType type, String selector, String expectedValue) {
        return new ResponseIndicator(
                "indicator-" + type.name(),
                type.name(),
                true,
                type,
                selector,
                expectedValue);
    }

    private void assertAllowIndicatorMatches(ResponseIndicator indicator, String rawResponse) {
        EvaluationProfile profile = profile(List.of(indicator), List.of(), List.of(), List.of());

        assertEquals(Verdict.OK, evaluator.evaluate(ExpectedResult.ALLOW, 200, rawResponse, profile).verdict());
    }

    private static String raw(int statusCode, String body) {
        return "HTTP/1.1 " + statusCode + " OK\n\n" + body;
    }
}
