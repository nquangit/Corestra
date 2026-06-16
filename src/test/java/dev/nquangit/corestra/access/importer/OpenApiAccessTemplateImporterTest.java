package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationType;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiAccessTemplateImporterTest {
    @Test
    void importsGetPathParamAndServerUrlAsDisabledTemplate() {
        OpenApiAccessTemplateImporter importer = importer();

        ImportResult result = importer.importTemplates(new ImportSource("api.json", """
                {
                  "openapi": "3.0.3",
                  "servers": [{"url": "https://api.example.test/v1"}],
                  "paths": {
                    "/accounts/{accountId}": {
                      "get": {
                        "operationId": "getAccount",
                        "parameters": [
                          {"name": "accountId", "in": "path", "required": true},
                          {"name": "includeCards", "in": "query"}
                        ]
                      }
                    }
                  }
                }
                """, ImportFormat.OPENAPI_JSON), ImportOptions.defaults());

        assertEquals(1, result.templates().size());
        AccessRequestTemplate template = result.templates().getFirst();
        assertEquals("getAccount", template.name());
        assertEquals("GET", template.baseRequest().method());
        assertEquals("https", template.baseRequest().scheme());
        assertEquals("api.example.test", template.baseRequest().host());
        assertEquals(443, template.baseRequest().port());
        assertEquals("/v1/accounts/{{data.accountId}}", template.baseRequest().pathAndQuery());
        assertEquals(OperationType.READ_ONLY, template.operationMetadata().operationType());
        assertTrue(template.placeholders().stream().anyMatch(ref -> ref.scopedKey().equals("data.accountId")));
        assertFalse(template.enabled());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("Query parameter omitted")));
    }

    @Test
    void importsPostBodyExampleAndSkipsDeprecatedByDefault() {
        OpenApiAccessTemplateImporter importer = importer();

        ImportResult result = importer.importTemplates(new ImportSource("api.json", """
                {
                  "openapi": "3.0.3",
                  "paths": {
                    "/transfers": {
                      "post": {
                        "operationId": "createTransfer",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "example": {
                                "accountId": "acct-1",
                                "requestId": "req-1",
                                "amount": 100
                              }
                            }
                          }
                        }
                      }
                    },
                    "/old": {
                      "get": {
                        "operationId": "oldEndpoint",
                        "deprecated": true
                      }
                    }
                  }
                }
                """, ImportFormat.OPENAPI_JSON), ImportOptions.defaults());

        assertEquals(1, result.templates().size());
        AccessRequestTemplate template = result.templates().getFirst();
        assertEquals("createTransfer", template.name());
        assertEquals("POST", template.baseRequest().method());
        assertEquals("example.test", template.baseRequest().host());
        assertEquals(OperationType.STATE_CHANGING, template.operationMetadata().operationType());
        assertTrue(template.baseRequest().headerLines().contains("Content-Type: application/json"));
        assertTrue(template.baseRequest().body().contains("{{data.accountId}}"));
        assertTrue(template.baseRequest().body().contains("{{runtime.uuid}}"));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("using import defaults")));
        assertEquals(1, result.skippedCount());
    }

    @Test
    void importsSwaggerJsonWithDefaults() {
        OpenApiAccessTemplateImporter importer = importer();

        ImportResult result = importer.importTemplates(new ImportSource("swagger.json", """
                {
                  "swagger": "2.0",
                  "host": "legacy.example.test",
                  "basePath": "/api",
                  "schemes": ["http"],
                  "paths": {
                    "/customers/{customerId}": {
                      "get": {
                        "operationId": "getCustomer",
                        "parameters": [{"name": "customerId", "in": "path"}]
                      }
                    }
                  }
                }
                """, ImportFormat.SWAGGER_JSON), ImportOptions.defaults());

        AccessRequestTemplate template = result.templates().getFirst();
        assertEquals("http", template.baseRequest().scheme());
        assertEquals("legacy.example.test", template.baseRequest().host());
        assertEquals(80, template.baseRequest().port());
        assertEquals("/api/customers/{{data.customerId}}", template.baseRequest().pathAndQuery());
    }

    private static OpenApiAccessTemplateImporter importer() {
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        return new OpenApiAccessTemplateImporter(ids);
    }
}
