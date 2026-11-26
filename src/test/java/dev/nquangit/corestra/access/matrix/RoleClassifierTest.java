package dev.nquangit.corestra.access.matrix;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleClassifierTest {
    private final RoleClassifier classifier = new RoleClassifier();

    @Test
    void recognisesAdministratorsByWholeWord() {
        assertTrue(classifier.isAdmin(profile("auth-1", "Admin Alice", "user")));
        assertTrue(classifier.isAdmin(profile("auth-1", "Alice", "administrator")));
        assertTrue(classifier.isAdmin(profile("auth-1", "Ops", "superAdmin")));
        assertTrue(classifier.isAdmin(profile("auth-root", "Ops", "")));
        assertTrue(classifier.isAdmin(profile("auth-1", "admin1", "")));
        assertTrue(classifier.isAdmin(profile("auth-1", "Ops", "Administrators")));
        assertTrue(classifier.isAdmin(profile("auth-1", "AWSAdmin", "")));
        assertTrue(classifier.isAdmin(profile("auth-1", "Bob", "orgadmin")));
        assertTrue(classifier.isAdmin(profile("auth-1", "Bob", "tenantadmin")));
        assertTrue(classifier.isAnonymous(profile("auth-1", "guest1", "")));
        assertTrue(classifier.isAnonymous(profile("auth-1", "Anon2", "")));
    }

    @Test
    void negatedOrEmbeddedAdminIsNotAdministrator() {
        assertFalse(classifier.isAdmin(profile("auth-1", "Non-admin clerk", "clerk")));
        assertFalse(classifier.isAdmin(profile("auth-1", "Clerk", "nonadmin")));
        assertFalse(classifier.isAdmin(profile("auth-1", "Clerk", "not admin")));
        assertFalse(classifier.isAdmin(profile("auth-1", "Badminton coach", "user")));
        assertFalse(classifier.isAdmin(profile("auth-1", "Groot", "user")));
    }

    @Test
    void recognisesAnonymousWithoutSubstringFalsePositives() {
        assertTrue(classifier.isAnonymous(profile("auth-anon", "Visitor", "")));
        assertTrue(classifier.isAnonymous(profile("auth-1", "Guest", "user")));
        assertFalse(classifier.isAnonymous(profile("auth-1", "Anonymised reports reader", "analyst")));
        assertFalse(classifier.isAnonymous(profile("auth-1", "Guesthouse owner", "owner")));
    }

    @Test
    void splitsCamelCaseAndSeparators() {
        assertEquals(List.of("super", "admin", "x", "1", "user"), RoleClassifier.tokens("superAdmin_x1-user"));
        assertEquals(List.of("aws", "admin"), RoleClassifier.tokens("AWSAdmin"));
    }

    private static AuthProfile profile(String id, String name, String role) {
        return new AuthProfile(id, name, role, "manual", Map.of(), AuthState.empty(), true);
    }
}
