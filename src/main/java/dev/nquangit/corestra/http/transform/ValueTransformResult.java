package dev.nquangit.corestra.http.transform;

/**
 * Result of applying an ordered value-transform chain.
 *
 * @param success whether every transform completed
 * @param value transformed value, or the last valid value on failure
 * @param error actionable failure description, or an empty string on success
 */
public record ValueTransformResult(boolean success, String value, String error) {
    /**
     * Creates a successful transform result.
     *
     * @param value transformed value
     * @return successful result
     */
    public static ValueTransformResult success(String value) {
        return new ValueTransformResult(true, value, "");
    }

    /**
     * Creates a failed transform result.
     *
     * @param value last valid value
     * @param error failure description
     * @return failed result
     */
    public static ValueTransformResult failure(String value, String error) {
        return new ValueTransformResult(false, value, error);
    }
}
