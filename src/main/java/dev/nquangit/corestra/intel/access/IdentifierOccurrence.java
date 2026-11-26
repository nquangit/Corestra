package dev.nquangit.corestra.intel.access;

import java.util.Objects;

/**
 * An object identifier found in a request, such as {@code 4821} in {@code /users/4821/orders}.
 *
 * @param location where the identifier is
 * @param selector path segment index (as text), query/form name, or JSON path ({@code a.b[0].c})
 * @param name suggested Data Profile key for the identifier, such as {@code userId}
 * @param value literal value, or the placeholder text when {@link #parameterized()}
 * @param kind value shape
 * @param parameterized whether the value is already a template placeholder ({@code {{data.x}}})
 */
public record IdentifierOccurrence(
        Location location,
        String selector,
        String name,
        String value,
        Kind kind,
        boolean parameterized) {

    /** Validates fields. */
    public IdentifierOccurrence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(kind, "kind");
        selector = Objects.requireNonNullElse(selector, "");
        name = Objects.requireNonNullElse(name, "");
        value = Objects.requireNonNullElse(value, "");
    }

    /** Where an identifier occurs. */
    public enum Location { PATH, QUERY, FORM, JSON }

    /** The shape of an identifier value. */
    public enum Kind { NUMERIC, UUID, OBJECT_ID, HEX, EMAIL, OPAQUE, PLACEHOLDER }

    /**
     * Returns a short description, for example {@code path segment 3 = 4821 (NUMERIC)}.
     *
     * @return description
     */
    public String describe() {
        String where = switch (location) {
            case PATH -> "path segment " + (Integer.parseInt(selector) + 1);
            case QUERY -> "query " + selector;
            case FORM -> "form " + selector;
            case JSON -> "JSON " + selector;
        };
        return where + " = " + value + " (" + kind + ")";
    }
}
