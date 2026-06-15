package dev.nquangit.corestra.session;

/**
 * Thread-local depth counter used to signal "session refresh is in progress on this thread."
 *
 * <p>Both {@code SessionAwareHttpClient} and {@code SessionManager} enter/exit this guard
 * around session-refresh work so that re-entrant session application is skipped, preventing
 * infinite recursion when auto-trigger prerequisite steps fire session handling again.
 *
 * <p>Uses a depth counter (not a boolean flag) so that nested enter/exit calls from different
 * callers on the same thread are tracked correctly.
 */
public final class SessionGuard {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private SessionGuard() {
        // Utility class — not instantiable
    }

    /**
     * Returns {@code true} if this thread is currently inside a session-refresh section.
     */
    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    /**
     * Marks entry into a session-refresh section. Must be paired with a corresponding
     * {@link #exit()} call, typically in a {@code try/finally} block.
     */
    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /**
     * Marks exit from a session-refresh section. When the depth counter reaches zero,
     * the ThreadLocal value is removed to avoid memory leaks in thread pools.
     */
    public static void exit() {
        int current = DEPTH.get();
        if (current <= 1) {
            DEPTH.remove();
        } else {
            DEPTH.set(current - 1);
        }
    }
}
