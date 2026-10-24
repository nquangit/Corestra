package dev.nquangit.corestra.interaction;

import java.util.Optional;

/** Boundary for asking the operator for a transient injection value. */
@FunctionalInterface
public interface InteractiveInputPrompt {
    /** Returns the entered value, or empty when cancelled; must respect interruption and the configured timeout. */
    Optional<String> ask(String ruleName, InteractiveInputConfig config);
}
