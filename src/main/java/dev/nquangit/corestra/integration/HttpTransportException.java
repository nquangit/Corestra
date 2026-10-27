package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.flow.execution.ExecutionFailureType;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import javax.net.ssl.SSLException;

/** Carries a structured send failure through transport decorators without inspecting localized error text. */
public final class HttpTransportException extends IllegalStateException {
    private final ExecutionFailureType failureType;

    /** Creates a transport failure while preserving its original cause. */
    public HttpTransportException(ExecutionFailureType type, String message, Throwable cause) {
        super(message, cause);
        this.failureType = java.util.Objects.requireNonNull(type);
    }

    /** Returns the category available to flow conditions. */
    public ExecutionFailureType failureType() { return failureType; }

    /** Distinguishes actual Java interruption from logical cancellation or a socket timeout. */
    public static boolean wasInterrupted(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException))) return true;
        }
        return false;
    }

    /** Classifies known exception classes in a bounded, cycle-safe cause chain. */
    public static ExecutionFailureType classify(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        ExecutionFailureType fallback = ExecutionFailureType.TRANSPORT_ERROR;
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof HttpTransportException typed) return typed.failureType();
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return ExecutionFailureType.TIMEOUT;
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException) return ExecutionFailureType.CANCELLED;
            if (cause instanceof UnknownHostException) return ExecutionFailureType.DNS_ERROR;
            if (cause instanceof ConnectException) return ExecutionFailureType.CONNECTION_REFUSED;
            if (cause instanceof SSLException) fallback = ExecutionFailureType.TLS_ERROR;
            else if (cause instanceof SocketException && fallback != ExecutionFailureType.TLS_ERROR) fallback = ExecutionFailureType.CONNECTION_ERROR;
        }
        return fallback;
    }
}
