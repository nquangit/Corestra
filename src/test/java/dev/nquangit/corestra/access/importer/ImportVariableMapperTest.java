package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.variable.VariableScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImportVariableMapperTest {
    @Test
    void mapsKnownVariableNamesToScopedReferences() {
        ImportVariableMapper mapper = new ImportVariableMapper();

        assertEquals(VariableScope.AUTH, mapper.mapVariable("token").ref().scope());
        assertEquals("accessToken", mapper.mapVariable("token").ref().key());
        assertEquals("csrfToken", mapper.mapVariable("csrfToken").ref().key());
        assertEquals(VariableScope.DATA, mapper.mapVariable("accountId").ref().scope());
        assertEquals("accountId", mapper.mapVariable("accountId").ref().key());
        assertEquals(VariableScope.RUNTIME, mapper.mapVariable("requestId").ref().scope());
        assertEquals("uuid", mapper.mapVariable("requestId").ref().key());
    }

    @Test
    void unknownVariableMapsToLegacyWithWarning() {
        ImportVariableMapper mapper = new ImportVariableMapper();
        List<ImportWarning> warnings = new ArrayList<>();

        String placeholder = mapper.placeholderFor("mystery", warnings, "header");

        assertEquals("{{legacy.mystery}}", placeholder);
        assertFalse(warnings.isEmpty());
    }
}
