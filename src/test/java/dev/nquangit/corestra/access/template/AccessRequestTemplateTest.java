package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessRequestTemplateTest {
    @Test
    void storesImmutableStoredRequestTemplateReferenceAndPlaceholders() {
        StoredRequestTemplate baseRequest = sampleRequest();
        Set<ScopedVariableRef> placeholders = new LinkedHashSet<>();
        ScopedVariableRef authRef = new ScopedVariableRef(VariableScope.AUTH, "accessToken");
        placeholders.add(authRef);

        AccessRequestTemplate template = new AccessRequestTemplate(
                "tpl-1",
                "Get account",
                baseRequest,
                placeholders,
                new OperationMetadata(OperationType.READ_ONLY, false, false, "read account"),
                "flow-1",
                "step-1",
                "hash-1",
                true);
        placeholders.add(new ScopedVariableRef(VariableScope.DATA, "accountId"));

        assertSame(baseRequest, template.baseRequest());
        assertEquals(Set.of(authRef), template.placeholders());
        assertThrows(UnsupportedOperationException.class,
                () -> template.placeholders().add(new ScopedVariableRef(VariableScope.DATA, "accountId")));
    }

    @Test
    void operationMetadataDefaultsToUnknown() {
        AccessRequestTemplate template = new AccessRequestTemplate(
                "tpl-1",
                "Get account",
                sampleRequest(),
                Set.of(),
                null,
                null,
                null,
                null,
                true);

        assertEquals(OperationType.UNKNOWN, template.operationMetadata().operationType());
        assertEquals("", template.sourceFlowId());
        assertEquals("", template.sourceStepId());
        assertEquals("", template.sourceRequestHash());
    }

    private static StoredRequestTemplate sampleRequest() {
        return new StoredRequestTemplate(
                "GET",
                "https",
                "example.test",
                443,
                "/accounts/{{data.accountId}}",
                List.of("Authorization: Bearer {{auth.accessToken}}"),
                "");
    }
}
