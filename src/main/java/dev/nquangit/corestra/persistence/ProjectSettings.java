package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import dev.nquangit.corestra.http.retry.LegacyGlobalRetryPolicy;
import dev.nquangit.corestra.http.retry.RetryConfig;
import dev.nquangit.corestra.session.SessionSelectionConfig;

/**
 * Immutable project-scoped settings for Corestra runtime behavior.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ProjectSettings {
    /**
     * Default Burp Proxy listener host used by Proxy Pipeline mode.
     */
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";

    /**
     * Default Burp Proxy listener port used by Proxy Pipeline mode.
     */
    public static final int DEFAULT_PROXY_PORT = 8080;

    private final HttpSendMode sendMode;
    private final String proxyHost;
    private final int proxyPort;
    private final SessionSelectionConfig sessionSelection;
    private final RetryConfig retry;

    /**
     * Creates project settings.
     *
     * @param sendMode transport route; defaults to {@link HttpSendMode#STANDARD} when absent
     * @param proxyHost proxy listener host; defaults to {@value #DEFAULT_PROXY_HOST} when blank
     * @param proxyPort proxy listener port; must be in {@code 1..65535}
     * @param sessionSelection live-request session choice; disabled when absent
     * @param retry live-traffic retry profiles; {@link RetryConfig#defaults()} (disabled) when absent
     */
    public ProjectSettings(HttpSendMode sendMode, String proxyHost, int proxyPort,
                           SessionSelectionConfig sessionSelection, RetryConfig retry) {
        this(sendMode, proxyHost, proxyPort, sessionSelection, retry, null);
    }

    /**
     * JSON creator. Projects saved with the first Global Retry format store a flat
     * {@code globalRetry} policy; it is migrated into a single profile when {@code retry} is absent.
     */
    @JsonCreator
    static ProjectSettings fromJson(
            @JsonProperty("sendMode") HttpSendMode sendMode,
            @JsonProperty("proxyHost") String proxyHost,
            @JsonProperty("proxyPort") int proxyPort,
            @JsonProperty("sessionSelection") SessionSelectionConfig sessionSelection,
            @JsonProperty("retry") RetryConfig retry,
            @JsonProperty("globalRetry") LegacyGlobalRetryPolicy legacyRetry) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, retry, legacyRetry);
    }

    private ProjectSettings(HttpSendMode sendMode, String proxyHost, int proxyPort,
                            SessionSelectionConfig sessionSelection, RetryConfig retry,
                            LegacyGlobalRetryPolicy legacyRetry) {
        this.sendMode = sendMode == null ? HttpSendMode.STANDARD : sendMode;
        this.proxyHost = normalizeHost(proxyHost);
        this.proxyPort = normalizePort(proxyPort);
        this.sessionSelection = Objects.requireNonNullElseGet(sessionSelection, SessionSelectionConfig::disabled);
        this.retry = retry != null ? retry : legacyRetry != null ? legacyRetry.toConfig() : RetryConfig.defaults();
    }

    /** Creates settings compatible with projects saved before the global retry policy existed. */
    public ProjectSettings(HttpSendMode sendMode, String proxyHost, int proxyPort,
                           SessionSelectionConfig sessionSelection) {
        this(sendMode, proxyHost, proxyPort, sessionSelection, (RetryConfig) null);
    }

    /** Creates settings compatible with projects without interactive session selection. */
    public ProjectSettings(HttpSendMode sendMode, String proxyHost, int proxyPort) {
        this(sendMode, proxyHost, proxyPort, null);
    }

    /** Returns live-request session choice settings. */
    public SessionSelectionConfig sessionSelection() { return sessionSelection; }

    /** Returns a copy with updated interactive selection conditions. */
    public ProjectSettings withSessionSelection(SessionSelectionConfig config) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, config, retry);
    }

    /** Returns the project-wide live-traffic retry profiles. */
    public RetryConfig retry() { return retry; }

    /** Returns a copy with updated live-traffic retry profiles. */
    public ProjectSettings withRetry(RetryConfig config) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, config);
    }

    /**
     * Returns default project settings.
     *
     * @return default settings
     */
    public static ProjectSettings defaults() {
        return new ProjectSettings(HttpSendMode.STANDARD, DEFAULT_PROXY_HOST, DEFAULT_PROXY_PORT);
    }

    /**
     * Returns the selected HTTP send mode.
     *
     * @return send mode
     */
    public HttpSendMode sendMode() {
        return sendMode;
    }

    /**
     * Returns the proxy listener host used by Proxy Pipeline mode.
     *
     * @return proxy host
     */
    public String proxyHost() {
        return proxyHost;
    }

    /**
     * Returns the proxy listener port used by Proxy Pipeline mode.
     *
     * @return proxy port
     */
    public int proxyPort() {
        return proxyPort;
    }

    /**
     * Returns a copy with a new send mode.
     *
     * @param sendMode send mode
     * @return updated settings
     */
    public ProjectSettings withSendMode(HttpSendMode sendMode) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, retry);
    }

    /**
     * Returns a copy with a new proxy host.
     *
     * @param proxyHost proxy host
     * @return updated settings
     */
    public ProjectSettings withProxyHost(String proxyHost) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, retry);
    }

    /**
     * Returns a copy with a new proxy port.
     *
     * @param proxyPort proxy port
     * @return updated settings
     */
    public ProjectSettings withProxyPort(int proxyPort) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, retry);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProjectSettings that)) {
            return false;
        }
        return proxyPort == that.proxyPort
                && sendMode == that.sendMode
                && Objects.equals(proxyHost, that.proxyHost)
                && Objects.equals(sessionSelection, that.sessionSelection)
                && Objects.equals(retry, that.retry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sendMode, proxyHost, proxyPort, sessionSelection, retry);
    }

    @Override
    public String toString() {
        return "ProjectSettings{sendMode=" + sendMode
                + ", proxyHost='" + proxyHost + "', proxyPort=" + proxyPort + '}';
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return DEFAULT_PROXY_HOST;
        }
        return host.strip();
    }

    private static int normalizePort(int port) {
        if (port == 0) {
            return DEFAULT_PROXY_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("proxyPort must be in range 1..65535");
        }
        return port;
    }
}
