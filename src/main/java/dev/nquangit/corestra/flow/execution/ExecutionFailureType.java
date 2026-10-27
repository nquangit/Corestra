package dev.nquangit.corestra.flow.execution;

/** Stable categories for execution conditions; HTTP error statuses are responses, not transport failures. */
public enum ExecutionFailureType {
    /** A response was obtained and the step completed. */ NONE,
    /** No response was supplied by the transport. */ NO_RESPONSE,
    /** A connect, read, or HTTP timeout was reported. */ TIMEOUT,
    /** The destination refused the connection. */ CONNECTION_REFUSED,
    /** A socket failed, including reset/abort errors whose exact meaning may be platform-dependent. */ CONNECTION_ERROR,
    /** Host name resolution failed. */ DNS_ERROR,
    /** TLS setup or negotiation failed. */ TLS_ERROR,
    /** A send failed without a more specific structured cause. */ TRANSPORT_ERROR,
    /** Burp's HTTP API was unavailable. */ API_UNAVAILABLE,
    /** Request construction or preparation failed before sending. */ REQUEST_PREPARATION,
    /** Required injection input or rendering was invalid. */ INPUT_ERROR,
    /** The selected session binding was invalid. */ SESSION_BINDING,
    /** Execution was cancelled or interrupted. */ CANCELLED,
    /** A legacy or unclassified failure. */ UNKNOWN
}
