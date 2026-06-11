package dev.nquangit.corestra.access.variable;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableScopeTest {
    @Test
    void resolvesOnlyCaseSensitiveLowercasePrefixes() {
        assertEquals(Optional.of(VariableScope.ENV), VariableScope.fromPrefix("env"));
        assertEquals(Optional.of(VariableScope.AUTH), VariableScope.fromPrefix("auth"));
        assertEquals(Optional.of(VariableScope.DATA), VariableScope.fromPrefix("data"));
        assertEquals(Optional.of(VariableScope.SCENARIO), VariableScope.fromPrefix("scenario"));
        assertEquals(Optional.of(VariableScope.RUNTIME), VariableScope.fromPrefix("runtime"));
        assertEquals(Optional.of(VariableScope.LEGACY), VariableScope.fromPrefix("legacy"));

        assertTrue(VariableScope.fromPrefix("AUTH").isEmpty());
        assertTrue(VariableScope.fromPrefix("Auth").isEmpty());
    }

    @Test
    void exposesLowercasePrefix() {
        assertEquals("auth", VariableScope.AUTH.prefix());
    }
}
