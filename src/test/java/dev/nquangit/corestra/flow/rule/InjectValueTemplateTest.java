package dev.nquangit.corestra.flow.rule;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InjectValueTemplateTest {
    @Test
    void rendersFlatKeysOnceWithoutRegexReplacementOrRecursiveExpansion() {
        String template = "Bearer {{ auth.refreshToken }} / {{local}} / {{auth.refreshToken}}";
        assertEquals(List.of("auth.refreshToken", "local"), InjectValueTemplate.variableKeys(template));
        assertEquals("Bearer $1\\token / {{literal}} / $1\\token", InjectValueTemplate.render(template,
                Map.of("auth.refreshToken", "$1\\token", "local", "{{literal}}")));
    }

    @Test
    void missingVariableRejectsWholeTemplateAndReportsOnlyKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InjectValueTemplate.render("{{known}}/{{missing}}", Map.of("known", "secret")));
        assertTrue(error.getMessage().contains("missing"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void rejectsMalformedPlaceholderSyntax() {
        for (String template : List.of("Bearer {{", "{{}}", "{{   }}", "{{auth. token}}",
                "{{{auth.token}}}", "{{auth.{{token}}}}", "token}}")) {
            assertThrows(IllegalArgumentException.class, () -> InjectValueTemplate.variableKeys(template), template);
        }
        assertEquals("", InjectValueTemplate.render("{{empty}}", Map.of("empty", "")));
    }

    @Test
    void renamesExactReferencesWithoutChangingLiteralOccurrences() {
        assertEquals("auth.token / {{token}} / {{auth.tokenExtra}}",
                InjectValueTemplate.rename("auth.token / {{auth.token}} / {{auth.tokenExtra}}",
                        Map.of("auth.token", "token")));
    }
}
