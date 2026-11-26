package dev.nquangit.corestra.intel.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationRequest;
import dev.nquangit.corestra.access.matrix.MatrixGenerationResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationStrategy;
import dev.nquangit.corestra.access.matrix.MatrixGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.template.RenderedAccessRequest;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.intel.access.AccessInsight.RiskLevel;
import dev.nquangit.corestra.intel.access.IdentifierOccurrence.Kind;
import dev.nquangit.corestra.intel.access.IdentifierOccurrence.Location;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessInsightServiceTest {
    private final ObjectIdentifierDetector detector = new ObjectIdentifierDetector();
    private final OperationClassifier classifier = new OperationClassifier();
    private final AccessInsightService service = new AccessInsightService();

    @Test
    void detectsIdentifiersInPathQueryFormAndJsonButNotRouteWordsOrVersions() {
        List<IdentifierOccurrence> found = detector.detect(request("POST",
                "/api/v2/users/4821/documents/3f2a9c1e-7b4d-4e8a-9f00-1a2b3c4d5e6f/change-password?page=2&accountId=77",
                List.of("Content-Type: application/json"),
                "{\"orderId\":\"A-1009\",\"items\":[{\"productId\":555}],\"note\":\"hello world\","
                        + "\"ref\":\"507f1f77bcf86cd799439011\"}"));

        assertEquals(List.of(
                "path segment 4 = 4821 (NUMERIC)",
                "path segment 6 = 3f2a9c1e-7b4d-4e8a-9f00-1a2b3c4d5e6f (UUID)",
                "query accountId = 77 (NUMERIC)",
                "JSON orderId = A-1009 (OPAQUE)",
                "JSON items[0].productId = 555 (NUMERIC)",
                "JSON ref = 507f1f77bcf86cd799439011 (OBJECT_ID)"),
                found.stream().map(IdentifierOccurrence::describe).toList());
        assertEquals("userId", found.getFirst().name());
        assertEquals("documentId", found.get(1).name());
    }

    @Test
    void reportsExistingPlaceholdersAsParameterized() {
        List<IdentifierOccurrence> found = detector.detect(request("PUT", "/users/{{data.userId}}",
                List.of("Content-Type: application/json"), "{\"accountId\": {{data.accountId}}, \"x\":1}"));

        assertTrue(found.stream().allMatch(IdentifierOccurrence::parameterized), found.toString());
        assertEquals(Kind.PLACEHOLDER, found.getFirst().kind());
        assertEquals(2, found.size());
    }

    @Test
    void classifiesOperations() {
        assertEquals(OperationType.READ_ONLY, op("GET", "/api/payments/12", ""));
        assertEquals(OperationType.STATE_CHANGING, op("GET", "/api/orders/12/cancel", ""));
        assertEquals(OperationType.CONSUMABLE, op("POST", "/api/coupons/redeem", "{}"));
        assertEquals(OperationType.CONSUMABLE, op("GET", "/api/rewards/claim", ""));
        assertEquals(OperationType.IDEMPOTENT, op("PUT", "/api/users/7", "{}"));
        assertEquals(OperationType.STATE_CHANGING, op("DELETE", "/api/users/7", ""));
        assertEquals(OperationType.READ_ONLY, op("POST", "/graphql", "{\"query\":\"query Me { me { id } }\"}"));
        assertEquals(OperationType.STATE_CHANGING, op("POST", "/graphql",
                "{\"query\":\"mutation Del { deleteUser(id: 1) }\"}"));
    }

    @Test
    void scoresIdorShapedAdminExportEndpointsHighAndPublicOnesLow() {
        AccessWorkspace workspace = workspace(List.of(
                template("t-high", "DELETE", "/api/admin/users/4821/export"),
                template("t-low", "GET", "/health")));
        HttpMessageSnapshot response = new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(),
                "{\"users\":[{\"email\":\"a@b.c\",\"emailVerified\":true,\"homeAddress\":\"x\"}]}");

        List<AccessInsight> insights = service.analyzeAll(workspace,
                template -> template.id().equals("t-high") ? Optional.of(response) : Optional.empty());

        AccessInsight high = insights.getFirst();
        assertEquals("t-high", high.templateId());
        assertEquals(RiskLevel.HIGH, high.level());
        assertEquals(List.of("users[*].email", "users[*].homeAddress"), high.sensitiveFieldPaths());
        Set<MatrixGenerationStrategy> strategies = new LinkedHashSet<>();
        high.strategies().forEach(recommendation -> strategies.add(recommendation.strategy()));
        assertTrue(strategies.containsAll(Set.of(MatrixGenerationStrategy.OWNER_VS_OTHERS,
                MatrixGenerationStrategy.ROLE_ESCALATION, MatrixGenerationStrategy.ANONYMOUS_ACCESS)), strategies::toString);
        assertTrue(high.operationDiffers());
        assertTrue(high.readinessIssues().stream().anyMatch(issue -> issue.contains("literal identifier")));

        AccessInsight low = insights.get(1);
        assertEquals(RiskLevel.LOW, low.level());
        assertTrue(low.strategies().stream().noneMatch(r -> r.strategy() == MatrixGenerationStrategy.ANONYMOUS_ACCESS));
    }

    @Test
    void parameterizedCapturedTemplateRendersTheOriginalRequestAndFeedsMatrixGeneration() {
        StoredRequestTemplate captured = request("PATCH", "/api/users/4821/orders?accountId=77&page=2",
                List.of("Content-Type: application/json"), "{\"orderId\":\"A-1009\",\"qty\":2}");
        List<IdentifierOccurrence> identifiers = detector.detect(captured);

        TemplateParameterizer.Result result = new TemplateParameterizer().parameterize(captured, identifiers);

        assertEquals("/api/users/{{data.userId}}/orders?accountId={{data.accountId}}&page=2",
                result.request().pathAndQuery());
        assertEquals("{\"orderId\":\"{{data.orderId}}\",\"qty\":2}", result.request().body());
        assertEquals(Map.of("userId", "4821", "accountId", "77", "orderId", "A-1009"), result.dataValues());
        assertTrue(result.skipped().isEmpty());

        AuthProfile owner = profile("auth-owner", "Owner", "user");
        AuthProfile other = profile("auth-other", "Other", "user");
        DataProfile data = new DataProfile("data-1", "Owner data", owner.id(), result.dataValues(), true);
        Set<ScopedVariableRef> placeholders = new LinkedHashSet<>(result.addedPlaceholders());
        AccessRequestTemplate parameterized = new AccessRequestTemplate("t1", "Orders", result.request(), placeholders,
                OperationMetadata.unknown(), "", "", "", true);

        RenderedAccessRequest rendered = new AccessRequestTemplateRenderer(new TemplateRenderer(
                new VariableResolver(new DefaultRuntimeValueProvider())))
                .render(parameterized, new AccessScopedVariableStoreFactory()
                        .from(Map.of(), owner.state(), data, Map.of(), Map.of()), RenderMode.STRICT);
        assertTrue(rendered.success());
        assertEquals(captured.pathAndQuery(), rendered.renderedRequest().pathAndQuery());
        assertEquals("{\"orderId\":\"A-1009\",\"qty\":2}", rendered.renderedRequest().body());

        AccessWorkspace workspace = AccessWorkspace.builder()
                .authProfile(owner).authProfile(other).dataProfile(data).template(parameterized).build();
        MatrixGenerationResult matrix = new MatrixGenerator(new UuidAccessIdGenerator()).generate(
                MatrixGenerationRequest.forStrategy(MatrixGenerationStrategy.OWNER_VS_OTHERS), workspace);
        assertEquals(2, matrix.rows().size(), matrix.warnings().toString());
        assertTrue(matrix.rows().stream().anyMatch(row -> row.expected() == ExpectedResult.DENY
                && row.target().targetAuthProfileId().equals("auth-other")));

        AccessInsight insight = service.analyze(parameterized, null, workspace);
        AccessInsight.StrategyRecommendation ownerVsOthers = insight.strategies().stream()
                .filter(r -> r.strategy() == MatrixGenerationStrategy.OWNER_VS_OTHERS).findFirst().orElseThrow();
        assertTrue(ownerVsOthers.ready(), ownerVsOthers.missingPrerequisites().toString());
        assertTrue(insight.literalIdentifiers().isEmpty());
    }

    @Test
    void numericJsonIdentifiersBecomeUnquotedPlaceholdersSoTheyRenderAsNumbers() {
        StoredRequestTemplate captured = request("POST", "/api/transfer",
                List.of("Content-Type: application/json"), "{\"fromAccountId\":1001,\"amount\":5}");

        TemplateParameterizer.Result result = new TemplateParameterizer()
                .parameterize(captured, detector.detect(captured));

        assertEquals("{\"fromAccountId\":{{data.fromAccountId}},\"amount\":5}", result.request().body());
        List<IdentifierOccurrence> again = detector.detect(result.request());
        assertTrue(again.stream().allMatch(IdentifierOccurrence::parameterized), again.toString());
        assertEquals(Location.JSON, again.getFirst().location());
    }

    @Test
    void parameterizationKeepsRawEncodingAndUntouchedDecimalsAndAvoidsExistingKeys() {
        StoredRequestTemplate captured = request("POST", "/api/users/4821/items?accountId=a%2Bb%40x.com",
                List.of("Content-Type: application/json"),
                "{\"orderId\":\"A-1009\",\"price\":10.50}");
        TemplateParameterizer parameterizer = new TemplateParameterizer();

        TemplateParameterizer.Result result = parameterizer.parameterize(captured, detector.detect(captured),
                Map.of("userId", "someone-else", "accountId", "a%2Bb%40x.com"));

        assertEquals("/api/users/{{data.userId2}}/items?accountId={{data.accountId}}", result.request().pathAndQuery());
        assertEquals("4821", result.dataValues().get("userId2"));
        assertEquals("a%2Bb%40x.com", result.dataValues().get("accountId"));
        assertEquals("A-1009", result.dataValues().get("orderId"));
        assertEquals("{\"orderId\":\"{{data.orderId}}\",\"price\":10.50}", result.request().body());

        String rendered = result.request().pathAndQuery() + " " + result.request().body();
        for (Map.Entry<String, String> value : result.dataValues().entrySet()) {
            rendered = rendered.replace("{{data." + value.getKey() + "}}", value.getValue());
        }
        assertEquals(captured.pathAndQuery() + " " + captured.body(), rendered);
    }

    @Test
    void sensitiveFieldNamesAreJudgedByLastWord() {
        assertTrue(AccessInsightService.isSensitiveField("email"));
        assertTrue(AccessInsightService.isSensitiveField("contact_phone"));
        assertTrue(AccessInsightService.isSensitiveField("accessToken"));
        assertFalse(AccessInsightService.isSensitiveField("emailVerified"));
        assertFalse(AccessInsightService.isSensitiveField("displayName"));
    }

    private OperationType op(String method, String path, String body) {
        return classifier.classify(request(method, path, List.of("Content-Type: application/json"), body))
                .metadata().operationType();
    }

    private static AccessWorkspace workspace(List<AccessRequestTemplate> templates) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder()
                .authProfile(profile("auth-admin", "Admin", "admin"))
                .authProfile(profile("auth-user", "User", "user"));
        templates.forEach(builder::template);
        return builder.build();
    }

    private static AccessRequestTemplate template(String id, String method, String path) {
        return new AccessRequestTemplate(id, id, request(method, path, List.of(), ""), Set.of(),
                OperationMetadata.unknown(), "", "", "", true);
    }

    private static AuthProfile profile(String id, String name, String role) {
        return new AuthProfile(id, name, role, "manual", Map.of(), AuthState.empty(), true);
    }

    private static StoredRequestTemplate request(String method, String path, List<String> headers, String body) {
        return new StoredRequestTemplate(method, "https", "app.test", 443, path, headers, body);
    }
}
