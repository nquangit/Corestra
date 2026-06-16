package dev.nquangit.corestra.access.id;

/**
 * Generates stable identifiers for access-testing objects.
 */
public interface AccessIdGenerator {
    /**
     * Creates a new identifier with the supplied prefix.
     *
     * @param prefix identifier prefix such as auth, data, tmpl, case, or result
     * @return a new identifier
     */
    String newId(String prefix);
}
