package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestTemplateFactoryTest {
    @Test
    void createsTemplateFromStoredRequestWithDiscoveredPlaceholdersAndUnknownMetadata() {
        AccessRequestTemplateFactory factory = factory();
        StoredRequestTemplate request = request("/accounts/{{data.accountId}}",
                List.of("Authorization: Bearer {{auth.accessToken}}"), "");

        AccessRequestTemplate template = factory.fromStoredRequest("Get account", request, null);

        assertTrue(template.id().startsWith("tmpl-"));
        assertEquals("Get account", template.name());
        assertSame(request, template.baseRequest());
        assertEquals(OperationType.UNKNOWN, template.operationMetadata().operationType());
        assertEquals("", template.sourceFlowId());
        assertEquals("", template.sourceStepId());
        assertEquals(new RequestTemplateHasher().hash(request), template.sourceRequestHash());
        assertEquals(Set.of(
                new ScopedVariableRef(VariableScope.DATA, "accountId"),
                new ScopedVariableRef(VariableScope.AUTH, "accessToken")), template.placeholders());
        assertEquals("/accounts/{{data.accountId}}", request.pathAndQuery());
    }

    @Test
    void createsTemplateFromFlowStepWithSourceFlowAndStepIds() {
        AccessRequestTemplateFactory factory = factory();
        StoredRequestTemplate request = request("/profile/{{auth.userId}}", List.of(), "");
        FlowStep step = new FlowStep(
                "step-1",
                "Get profile",
                true,
                "",
                request,
                List.of(),
                List.of(),
                List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW);

        AccessRequestTemplate template = factory.fromFlowStep("flow-1", step,
                new OperationMetadata(OperationType.READ_ONLY, false, false, "profile read"));

        assertEquals("flow-1", template.sourceFlowId());
        assertEquals("step-1", template.sourceStepId());
        assertEquals("Get profile", template.name());
        assertSame(request, template.baseRequest());
        assertEquals(OperationType.READ_ONLY, template.operationMetadata().operationType());
        assertEquals(Set.of(new ScopedVariableRef(VariableScope.AUTH, "userId")), template.placeholders());
        assertEquals("/profile/{{auth.userId}}", step.requestTemplate().pathAndQuery());
    }

    private static AccessRequestTemplateFactory factory() {
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator idGenerator = prefix -> prefix + "-" + counter.incrementAndGet();
        AccessRequestTemplateAnalyzer analyzer = new AccessRequestTemplateAnalyzer(
                new TemplateRenderer(new VariableResolver(key -> Optional.empty())));
        return new AccessRequestTemplateFactory(idGenerator, analyzer, new RequestTemplateHasher(), RenderMode.STRICT);
    }

    private static StoredRequestTemplate request(String path, List<String> headers, String body) {
        return new StoredRequestTemplate("GET", "https", "example.test", 443, path, headers, body);
    }
}
