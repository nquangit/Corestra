package dev.nquangit.corestra.access.importer;

import java.util.Objects;

/**
 * Controls how external API definitions are converted into access templates.
 */
public final class ImportOptions {
    private final String defaultScheme;
    private final String defaultHost;
    private final int defaultPort;
    private final String basePathOverride;
    private final boolean disabledByDefault;
    private final boolean includeDeprecatedOperations;
    private final boolean generatePlaceholdersForPathParams;
    private final boolean generatePlaceholdersForQueryParams;
    private final boolean generatePlaceholdersForRequestBodyExamples;

    /**
     * Creates default import options.
     */
    public ImportOptions() {
        this("https", "example.test", 443, "", true, false, true, false, true);
    }

    /**
     * Creates import options.
     *
     * @param defaultScheme fallback URL scheme
     * @param defaultHost fallback host
     * @param defaultPort fallback port
     * @param basePathOverride optional base path override
     * @param disabledByDefault whether imported templates are disabled
     * @param includeDeprecatedOperations whether deprecated operations are imported
     * @param generatePlaceholdersForPathParams whether path params become placeholders
     * @param generatePlaceholdersForQueryParams whether query params become placeholders
     * @param generatePlaceholdersForRequestBodyExamples whether request examples get placeholder values
     */
    public ImportOptions(
            String defaultScheme,
            String defaultHost,
            int defaultPort,
            String basePathOverride,
            boolean disabledByDefault,
            boolean includeDeprecatedOperations,
            boolean generatePlaceholdersForPathParams,
            boolean generatePlaceholdersForQueryParams,
            boolean generatePlaceholdersForRequestBodyExamples) {
        this.defaultScheme = normalizeDefault(defaultScheme, "https");
        this.defaultHost = normalizeDefault(defaultHost, "example.test");
        this.defaultPort = defaultPort < 1 || defaultPort > 65535 ? defaultPortForScheme(this.defaultScheme) : defaultPort;
        this.basePathOverride = normalizeOptionalPath(basePathOverride);
        this.disabledByDefault = disabledByDefault;
        this.includeDeprecatedOperations = includeDeprecatedOperations;
        this.generatePlaceholdersForPathParams = generatePlaceholdersForPathParams;
        this.generatePlaceholdersForQueryParams = generatePlaceholdersForQueryParams;
        this.generatePlaceholdersForRequestBodyExamples = generatePlaceholdersForRequestBodyExamples;
    }

    /**
     * Returns default import options.
     *
     * @return default import options
     */
    public static ImportOptions defaults() {
        return new ImportOptions();
    }

    /**
     * Returns fallback URL scheme.
     *
     * @return default scheme
     */
    public String defaultScheme() {
        return defaultScheme;
    }

    /**
     * Returns fallback host.
     *
     * @return default host
     */
    public String defaultHost() {
        return defaultHost;
    }

    /**
     * Returns fallback port.
     *
     * @return default port
     */
    public int defaultPort() {
        return defaultPort;
    }

    /**
     * Returns optional base path override.
     *
     * @return base path override, or an empty string
     */
    public String basePathOverride() {
        return basePathOverride;
    }

    /**
     * Returns whether imported templates are disabled.
     *
     * @return true when disabled by default
     */
    public boolean disabledByDefault() {
        return disabledByDefault;
    }

    /**
     * Returns whether deprecated operations are imported.
     *
     * @return true when deprecated operations are imported
     */
    public boolean includeDeprecatedOperations() {
        return includeDeprecatedOperations;
    }

    /**
     * Returns whether path params become scoped placeholders.
     *
     * @return true when path params become placeholders
     */
    public boolean generatePlaceholdersForPathParams() {
        return generatePlaceholdersForPathParams;
    }

    /**
     * Returns whether query params become scoped placeholders.
     *
     * @return true when query params become placeholders
     */
    public boolean generatePlaceholdersForQueryParams() {
        return generatePlaceholdersForQueryParams;
    }

    /**
     * Returns whether request example values become scoped placeholders.
     *
     * @return true when request examples are placeholderized
     */
    public boolean generatePlaceholdersForRequestBodyExamples() {
        return generatePlaceholdersForRequestBodyExamples;
    }

    private static String normalizeDefault(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalizeOptionalPath(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static int defaultPortForScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) ? 80 : 443;
    }
}
