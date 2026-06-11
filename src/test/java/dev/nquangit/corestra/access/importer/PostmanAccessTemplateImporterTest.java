package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmanAccessTemplateImporterTest {
    @Test
    void importsNestedPostmanRequestsHeadersAndRawBody() {
        PostmanAccessTemplateImporter importer = importer();

        ImportResult result = importer.importTemplates(new ImportSource("collection.json", """
                {
                  "info": {
                    "name": "Access API",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [
                    {
                      "name": "Accounts",
                      "item": [
                        {
                          "name": "Get Account",
                          "event": [{"listen": "prerequest", "script": {"exec": ["pm.sendRequest(...)"]}}],
                          "request": {
                            "method": "GET",
                            "header": [
                              {"key": "X-Request-Id", "value": "{{requestId}}"},
                              {"key": "X-Unknown", "value": "{{mystery}}"}
                            ],
                            "url": {
                              "protocol": "https",
                              "host": ["{{baseUrl}}"],
                              "path": ["accounts", "{{accountId}}"]
                            }
                          }
                        },
                        {
                          "name": "Create Transfer",
                          "request": {
                            "method": "POST",
                            "auth": {"type": "bearer"},
                            "header": [{"key": "Authorization", "value": "Bearer {{token}}"}],
                            "body": {
                              "mode": "raw",
                              "raw": "{\\"accountId\\":\\"{{accountId}}\\"}",
                              "options": {"raw": {"language": "json"}}
                            },
                            "url": "{{baseUrl}}/transfers"
                          }
                        }
                      ]
                    }
                  ]
                }
                """, ImportFormat.POSTMAN_COLLECTION_JSON), ImportOptions.defaults());

        assertEquals(2, result.templates().size());
        AccessRequestTemplate get = result.templates().getFirst();
        assertEquals("Accounts / Get Account", get.name());
        assertEquals("{{env.baseUrl}}", get.baseRequest().host());
        assertEquals("/accounts/{{data.accountId}}", get.baseRequest().pathAndQuery());
        assertTrue(get.baseRequest().headerLines().contains("X-Request-Id: {{runtime.uuid}}"));
        assertTrue(get.baseRequest().headerLines().contains("X-Unknown: {{legacy.mystery}}"));
        assertEquals(OperationType.READ_ONLY, get.operationMetadata().operationType());
        assertFalse(get.enabled());

        AccessRequestTemplate post = result.templates().get(1);
        assertEquals("POST", post.baseRequest().method());
        assertEquals(OperationType.STATE_CHANGING, post.operationMetadata().operationType());
        assertTrue(post.baseRequest().headerLines().contains("Authorization: Bearer {{auth.accessToken}}"));
        assertTrue(post.baseRequest().body().contains("{{data.accountId}}"));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("script")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("auth helper")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("legacy.mystery")));
    }

    private static PostmanAccessTemplateImporter importer() {
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        return new PostmanAccessTemplateImporter(ids);
    }
}
