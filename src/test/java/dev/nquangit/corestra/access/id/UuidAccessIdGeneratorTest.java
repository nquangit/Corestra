package dev.nquangit.corestra.access.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidAccessIdGeneratorTest {
    @Test
    void generatedIdsUseExpectedPrefixAndAreUnique() {
        UuidAccessIdGenerator generator = new UuidAccessIdGenerator();
        Set<String> ids = new HashSet<>();

        for (int index = 0; index < 20; index++) {
            String id = generator.newId("auth");
            assertTrue(id.startsWith("auth-"));
            ids.add(id);
        }

        assertEquals(20, ids.size());
    }
}
