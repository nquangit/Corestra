package dev.nquangit.corestra.integration.watch;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.integration.CorestraTrafficOrigin;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchProbeClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends Watch probes through Montoya as Corestra-origin traffic.
 *
 * <p>The template is rendered by {@link WatchProbeRequestRenderer} (unresolved placeholders fail
 * without sending), the send is wrapped in {@link CorestraTrafficOrigin#send} so that no Corestra
 * handler (Watch, Auto Trigger, Session Handler, ...) reprocesses it, Burp's response timeout is set
 * with {@link RequestOptions#withResponseTimeout(long)}, and the whole send is additionally bounded
 * by a worker-thread wait of {@code timeoutMillis} plus a short grace period. Never throws.</p>
 */
public final class MontoyaWatchProbeClient implements WatchProbeClient, AutoCloseable {
    /** Extra wait beyond the probe timeout before the worker wait gives up. */
    static final long GRACE_MILLIS = 1_000L;

    /** Transport seam: sends one prepared request with a response timeout. */
    @FunctionalInterface
    interface Transport {
        HttpResponse send(HttpRequest request, long timeoutMillis);
    }

    private final Transport transport;
    private final CorestraTrafficOrigin origin;
    private final ExecutorService executor;

    /**
     * Creates a probe client over Burp's HTTP API.
     *
     * @param api    Montoya API
     * @param origin shared, registered Corestra origin guard (marks the probe as internal traffic)
     */
    public MontoyaWatchProbeClient(MontoyaApi api, CorestraTrafficOrigin origin) {
        this(nativeTransport(Objects.requireNonNull(api, "api")), Objects.requireNonNull(origin, "origin"));
    }

    MontoyaWatchProbeClient(Transport transport, CorestraTrafficOrigin origin) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.origin = origin;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "corestra-watch-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ProbeResponse fetch(WatchDefinition definition, Map<String, String> variables,
                               List<String> inheritedHeaders, long timeoutMillis) {
        try {
            if (definition == null || definition.probe() == null || !definition.probe().configured()) {
                return ProbeResponse.failure("probe request is not configured");
            }
            WatchProbeRequestRenderer.Rendered rendered;
            try {
                rendered = WatchProbeRequestRenderer.render(definition.probe().request(), variables, inheritedHeaders);
            } catch (IllegalArgumentException unresolved) {
                return ProbeResponse.failure(unresolved.getMessage());
            }
            HttpRequest request = HttpRequest.httpRequest(
                    HttpService.httpService(rendered.host(), rendered.port(), rendered.secure()), rendered.rawRequest());
            long timeout = Math.max(1L, timeoutMillis);
            Future<HttpResponse> future;
            try {
                future = executor.submit(() -> origin == null ? transport.send(request, timeout)
                        : origin.send(request, marked -> transport.send(marked, timeout)));
            } catch (RejectedExecutionException closed) {
                return ProbeResponse.failure("probe client is closed");
            }
            HttpResponse response;
            try {
                response = future.get(timeout + GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                future.cancel(true);
                return ProbeResponse.failure("probe timed out after " + timeout + " ms");
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return ProbeResponse.failure("probe was interrupted");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                return ProbeResponse.failure("probe send failed: " + describe(cause));
            }
            if (response == null) return ProbeResponse.failure("probe received no response");
            List<String> headers = response.headers().stream().map(h -> h.name() + ": " + h.value()).toList();
            return new ProbeResponse(response.statusCode(), headers, response.bodyToString(), null);
        } catch (RuntimeException unexpected) {
            return ProbeResponse.failure("probe failed: " + describe(unexpected));
        }
    }

    /** Stops the worker threads; later fetches fail without sending. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static Transport nativeTransport(MontoyaApi api) {
        return (request, timeout) -> {
            HttpRequestResponse exchange = api.http().sendRequest(request,
                    RequestOptions.requestOptions().withResponseTimeout(timeout));
            if (exchange == null || exchange.response() == null) {
                throw new IllegalStateException("no response (timeout or connection failure)");
            }
            return exchange.response();
        };
    }
}
