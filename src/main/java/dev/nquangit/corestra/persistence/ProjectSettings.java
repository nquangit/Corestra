package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import dev.nquangit.corestra.http.retry.GlobalRetryPolicy;
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
    private final GlobalRetryPolicy globalRetry;

    /**
     * Creates project settings.
     *
     * @param sendMode transport route; defaults to {@link HttpSendMode#STANDARD} when absent
     * @param proxyHost proxy listener host; defaults to {@value #DEFAULT_PROXY_HOST} when blank
     * @param proxyPort proxy listener port; must be in {@code 1..65535}
     * @param sessionSelection live-request session choice; disabled when absent
     * @param globalRetry live-traffic retry policy; {@link GlobalRetryPolicy#defaults()} (disabled) when absent
     */
    @JsonCreator
    public ProjectSettings(
            @JsonProperty("sendMode") HttpSendMode sendMode,
            @JsonProperty("proxyHost") String proxyHost,
            @JsonProperty("proxyPort") int proxyPort,
            @JsonProperty("sessionSelection") SessionSelectionConfig sessionSelection,
            @JsonProperty("globalRetry") GlobalRetryPolicy globalRetry) {
        this.sendMode = sendMode == null ? HttpSendMode.STANDARD : sendMode;
        this.proxyHost = normalizeHost(proxyHost);
        this.proxyPort = normalizePort(proxyPort);
        this.sessionSelection = Objects.requireNonNullElseGet(sessionSelection, SessionSelectionConfig::disabled);
        this.globalRetry = Objects.requireNonNullElseGet(globalRetry, GlobalRetryPolicy::defaults);
    }

    /** Creates settings compatible with projects saved before the global retry policy existed. */
    public ProjectSettings(HttpSendMode sendMode, String proxyHost, int proxyPort,
                           SessionSelectionConfig sessionSelection) {
        this(sendMode, proxyHost, proxyPort, sessionSelection, null);
    }

    /** Creates settings compatible with projects without interactive session selection. */
    public ProjectSettings(HttpSendMode sendMode, String proxyHost, int proxyPort) {
        this(sendMode, proxyHost, proxyPort, null);
    }

    /** Returns live-request session choice settings. */
    public SessionSelectionConfig sessionSelection() { return sessionSelection; }

    /** Returns a copy with updated interactive selection conditions. */
    public ProjectSettings withSessionSelection(SessionSelectionConfig config) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, config, globalRetry);
    }

    /** Returns the project-wide live-traffic retry policy. */
    public GlobalRetryPolicy globalRetry() { return globalRetry; }

    /** Returns a copy with an updated live-traffic retry policy. */
    public ProjectSettings withGlobalRetry(GlobalRetryPolicy policy) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, policy);
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
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, globalRetry);
    }

    /**
     * Returns a copy with a new proxy host.
     *
     * @param proxyHost proxy host
     * @return updated settings
     */
    public ProjectSettings withProxyHost(String proxyHost) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, globalRetry);
    }

    /**
     * Returns a copy with a new proxy port.
     *
     * @param proxyPort proxy port
     * @return updated settings
     */
    public ProjectSettings withProxyPort(int proxyPort) {
        return new ProjectSettings(sendMode, proxyHost, proxyPort, sessionSelection, globalRetry);
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
                && Objects.equals(globalRetry, that.globalRetry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sendMode, proxyHost, proxyPort, sessionSelection, globalRetry);
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
