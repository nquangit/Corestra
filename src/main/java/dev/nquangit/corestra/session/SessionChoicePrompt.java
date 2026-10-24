package dev.nquangit.corestra.session;

import java.util.List;
import java.util.Optional;

/** Operator boundary for choosing a session; implementations must bound waiting and support cancellation. */
@FunctionalInterface
public interface SessionChoicePrompt {
    /** Returns the chosen profile id, or empty on cancellation; choices contain no authentication secrets. */
    Optional<String> choose(SessionTrafficContext context, List<SessionChoice> choices, int timeoutSeconds);
}
