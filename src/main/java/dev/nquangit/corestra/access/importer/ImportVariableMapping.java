package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.variable.ScopedVariableRef;

import java.util.Objects;

/**
 * Describes how an external variable name maps to a scoped Corestra variable.
 */
public final class ImportVariableMapping {
    private final String originalName;
    private final ScopedVariableRef ref;
    private final boolean certain;

    /**
     * Creates a variable mapping.
     *
     * @param originalName external variable name
     * @param ref scoped variable reference
     * @param certain whether the mapping is a known safe mapping
     */
    public ImportVariableMapping(String originalName, ScopedVariableRef ref, boolean certain) {
        this.originalName = normalizeRequired(originalName, "originalName");
        this.ref = Objects.requireNonNull(ref, "ref");
        this.certain = certain;
    }

    /**
     * Returns the external variable name.
     *
     * @return external variable name
     */
    public String originalName() {
        return originalName;
    }

    /**
     * Returns the scoped variable reference.
     *
     * @return scoped variable reference
     */
    public ScopedVariableRef ref() {
        return ref;
    }

    /**
     * Returns whether this mapping is a known safe mapping.
     *
     * @return true when the mapping is certain
     */
    public boolean certain() {
        return certain;
    }

    /**
     * Returns the template placeholder.
     *
     * @return template placeholder
     */
    public String placeholder() {
        return "{{" + ref.scopedKey() + "}}";
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
