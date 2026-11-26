package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.intel.access.AccessInsight;
import dev.nquangit.corestra.intel.access.AccessInsightService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateInsightsDialogTest {

    @Test
    void detailsExplainScoreIdentifiersStrategiesAndMissingSetup() {
        AccessRequestTemplate template = new AccessRequestTemplate("t1", "Get invoice",
                new StoredRequestTemplate("GET", "https", "app.test", 443, "/api/invoices/991", List.of(), ""),
                Set.of(), OperationMetadata.unknown(), "", "", "", false);
        AccessWorkspace workspace = AccessWorkspace.builder()
                .authProfile(new AuthProfile("a1", "Alice", "user", "manual", Map.of(), AuthState.empty(), true))
                .template(template)
                .build();
        AccessInsight insight = new AccessInsightService().analyze(template, null, workspace);

        String text = TemplateInsightsDialog.describe(insight);

        assertTrue(text.contains("+35 object identifier in the path"), text);
        assertTrue(text.contains("path segment 3 = 991 (NUMERIC)  → {{data.invoiceId}}"), text);
        assertTrue(text.contains("OWNER_VS_OTHERS"), text);
        assertTrue(text.contains("needs: Enable the template"), text);
        assertTrue(text.contains("Template is disabled"), text);
    }
}
