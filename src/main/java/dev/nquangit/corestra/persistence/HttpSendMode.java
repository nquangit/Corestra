package dev.nquangit.corestra.persistence;

/**
 * Selects the transport route Corestra uses for extension-generated HTTP traffic.
 */
public enum HttpSendMode {
    /**
     * Send through Montoya's standard {@code api.http().sendRequest(...)} API.
     */
    STANDARD,

    /**
     * Send through a configured Burp Proxy listener so proxy-oriented tooling can process traffic.
     */
    PROXY_PIPELINE
}
