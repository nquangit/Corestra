package dev.nquangit.corestra.intel;

/**
 * How strongly an Intelligence suggestion is supported by the captured evidence.
 */
public enum SuggestionConfidence {
    /** Distinctive evidence (tokens, UUIDs, JWTs, long random values); safe to pre-select. */
    HIGH,
    /** Plausible evidence (numeric identifiers, medium-length values); review before applying. */
    MEDIUM,
    /** Weak or ambiguous evidence (short values, values the user typed earlier); not pre-selected. */
    LOW
}
