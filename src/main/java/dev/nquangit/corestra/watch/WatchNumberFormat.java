package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How a textual number (often money) is normalized to an exact decimal.
 *
 * <p>Examples: {@code "1,234,567.89"} (group {@code ","}, decimal {@code "."});
 * {@code "1.234.567,89 VND"} (group {@code "."}, decimal {@code ","}, strip {@code "[^0-9.,-]"});
 * integer cents {@code 123456} with {@code minorUnitScale = 2} becomes {@code 1234.56}.
 * When {@code auto} is true the parser infers separators from the text and the separator
 * fields are ignored; {@code stripPattern} and {@code minorUnitScale} still apply.</p>
 *
 * @param auto             infer decimal/group separators from the value
 * @param decimalSeparator decimal separator, e.g. "." or ","
 * @param groupSeparator   grouping separator, e.g. ",", ".", " " or "" for none
 * @param minorUnitScale   0 when values are in major units; n to divide by 10^n
 * @param stripPattern     Java regex of characters removed before parsing (currency symbols…)
 */
public record WatchNumberFormat(boolean auto, String decimalSeparator, String groupSeparator,
                                int minorUnitScale, String stripPattern) {
    /**
     * Normalizes null fields and clamps the scale to 0..8.
     *
     * @param auto             automatic separators
     * @param decimalSeparator decimal separator
     * @param groupSeparator   group separator
     * @param minorUnitScale   minor unit scale
     * @param stripPattern     strip regex
     */
    @JsonCreator
    public WatchNumberFormat(@JsonProperty("auto") boolean auto,
                             @JsonProperty("decimalSeparator") String decimalSeparator,
                             @JsonProperty("groupSeparator") String groupSeparator,
                             @JsonProperty("minorUnitScale") int minorUnitScale,
                             @JsonProperty("stripPattern") String stripPattern) {
        this.auto = auto;
        this.decimalSeparator = decimalSeparator == null || decimalSeparator.isEmpty() ? "." : decimalSeparator;
        this.groupSeparator = groupSeparator == null ? "," : groupSeparator;
        this.minorUnitScale = Math.max(0, Math.min(8, minorUnitScale));
        this.stripPattern = stripPattern == null ? "" : stripPattern;
    }

    /**
     * Returns automatic separator detection with no scaling.
     *
     * @return automatic format
     */
    public static WatchNumberFormat automatic() {
        return new WatchNumberFormat(true, ".", ",", 0, "[^0-9.,\\-]");
    }
}
