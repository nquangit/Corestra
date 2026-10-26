package dev.nquangit.corestra.http.discovery;

import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import dev.nquangit.corestra.integration.ProxyPipelineHttpClient;
import java.util.*;
import java.util.function.*;

/** Rebuilds runtime vocabulary from saved Proxy history, without network sends or Site Map/scanner imports. */
public final class ProxyHistoryWordlistRescan {
    private final Supplier<List<ProxyHttpRequestResponse>> history;
    private final ParameterDiscoveryHandler collector;
    /** Connects the saved-history provider and the existing live collector policy. */
    public ProxyHistoryWordlistRescan(Supplier<List<ProxyHttpRequestResponse>> history, ParameterDiscoveryHandler collector) {
        this.history = Objects.requireNonNull(history); this.collector = Objects.requireNonNull(collector);
    }
    /** Summary counts history records, rather than HTTP callback messages. */
    public record Result(int examined, int collected, int skipped, boolean cancelled) { }
    /** Scans stored original requests. Edited entries and original/final pipeline markers are excluded. */
    public Result scan(BooleanSupplier cancelled, IntConsumer progress) {
        int examined = 0, collected = 0, skipped = 0;
        for (ProxyHttpRequestResponse record : history.get()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return new Result(examined, collected, skipped, true);
            examined++;
            try {
                var original = record.request(); var sent = record.finalRequest();
                if (record.edited() || original == null || original.hasHeader(ProxyPipelineHttpClient.MARKER_HEADER)
                        || (sent != null && sent.hasHeader(ProxyPipelineHttpClient.MARKER_HEADER))) { skipped++; continue; }
                if (collector.scanStored(original, record.hasResponse() ? record.response() : null)) collected++; else skipped++;
            } catch (RuntimeException unreadable) { skipped++; }
            finally { if (examined % 100 == 0) progress.accept(examined); }
        }
        progress.accept(examined); return new Result(examined, collected, skipped, cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
    }
}
