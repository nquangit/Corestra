package dev.nquangit.corestra.interaction;

/** Configuration of an input prompt; entered values are never part of this configuration. */
public record InteractiveInputConfig(String prompt, boolean secret, int timeoutSeconds) {
    /** Creates a prompt with a bounded wait; omitted timeouts default to five minutes. */
    public InteractiveInputConfig {
        prompt = prompt == null ? "" : prompt.strip();
        timeoutSeconds = timeoutSeconds == 0 ? 300 : timeoutSeconds;
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("Interactive timeout must be between 1 and 3600 seconds");
        }
    }

    /** Default input prompt with masked entry. */
    public static InteractiveInputConfig defaults() {
        return new InteractiveInputConfig("", true, 300);
    }
}
