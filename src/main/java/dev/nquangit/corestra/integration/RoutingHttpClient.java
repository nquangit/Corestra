package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectSettings;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Routes Corestra HTTP sends to either the standard Montoya transport or the proxy pipeline.
 */
public final class RoutingHttpClient implements HttpClient {
    private final Supplier<ProjectSettings> settingsSupplier;
    private final HttpClient standardClient;
    private final HttpClient proxyPipelineClient;

    /**
     * Creates a routing HTTP client.
     *
     * @param settingsSupplier supplies the current project settings
     * @param standardClient standard Montoya-backed client
     * @param proxyPipelineClient proxy-pipeline client
     */
    public RoutingHttpClient(
            Supplier<ProjectSettings> settingsSupplier,
            HttpClient standardClient,
            HttpClient proxyPipelineClient) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.standardClient = Objects.requireNonNull(standardClient, "standardClient");
        this.proxyPipelineClient = Objects.requireNonNull(proxyPipelineClient, "proxyPipelineClient");
    }

    /**
     * Sends the request using the route selected by current project settings.
     *
     * @param request the request to send
     * @return the response received
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        ProjectSettings settings = Objects.requireNonNullElse(settingsSupplier.get(), ProjectSettings.defaults());
        if (settings.sendMode() == HttpSendMode.PROXY_PIPELINE) {
            return proxyPipelineClient.send(request);
        }
        return standardClient.send(request);
    }
}
