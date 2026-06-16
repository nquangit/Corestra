package dev.nquangit.corestra.flow.control;

/**
 * Identifies the value source inspected by a step condition.
 */
public enum StepConditionSource {
    /**
     * Reads the HTTP status code from the step response.
     */
    RESPONSE_STATUS,

    /**
     * Reads a response header value by name.
     */
    RESPONSE_HEADER,

    /**
     * Reads the full response body.
     */
    RESPONSE_BODY,

    /**
     * Reads a response JSON body value through Corestra's dot-path selector.
     */
    RESPONSE_JSON_PATH,

    /**
     * Reads a response body field from urlencoded form data.
     */
    RESPONSE_FORM_FIELD,

    /**
     * Reads a rendered request header value by name.
     */
    REQUEST_HEADER,

    /**
     * Reads the rendered request body.
     */
    REQUEST_BODY,

    /**
     * Reads a rendered request JSON body value through Corestra's dot-path selector.
     */
    REQUEST_JSON_PATH,

    /**
     * Reads a rendered request body field from urlencoded form data.
     */
    REQUEST_FORM_FIELD,

    /**
     * Reads a variable from the current flow execution context.
     */
    VARIABLE
}
