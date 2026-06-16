package dev.nquangit.corestra.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionGuardTest {

    @AfterEach
    void cleanup() {
        // Ensure thread-local is clean after each test regardless of outcome
        while (SessionGuard.isActive()) {
            SessionGuard.exit();
        }
    }

    @Test
    void isActive_returnsFalse_initially() {
        assertFalse(SessionGuard.isActive());
    }

    @Test
    void enter_makesIsActiveTrue() {
        SessionGuard.enter();
        assertTrue(SessionGuard.isActive());
    }

    @Test
    void exit_afterEnter_makesIsActiveFalse() {
        SessionGuard.enter();
        SessionGuard.exit();
        assertFalse(SessionGuard.isActive());
    }

    @Test
    void nesting_works() {
        SessionGuard.enter();
        SessionGuard.enter();
        assertTrue(SessionGuard.isActive(), "Still active after first exit");
        SessionGuard.exit();
        assertTrue(SessionGuard.isActive(), "Still active after first exit");
        SessionGuard.exit();
        assertFalse(SessionGuard.isActive(), "Inactive after all exits");
    }

    @Test
    void exit_withoutEnter_doesNotThrow() {
        // depth is already 0 — exit should not throw
        SessionGuard.exit();
        assertFalse(SessionGuard.isActive());
    }
}
