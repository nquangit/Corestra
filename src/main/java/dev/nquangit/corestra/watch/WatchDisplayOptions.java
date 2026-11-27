package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Where a Watch result is shown on the Repeater response. Any combination may be enabled.
 *
 * @param headers      add {@code X-Corestra-Watch*} summary headers
 * @param jsonEnvelope wrap JSON bodies as {@code {"corestraWatch":{...},"response":<original>}}
 * @param textBlock    prepend a plain-text result block to the body
 * @param annotations  set Burp notes and a highlight colour (visible in Logger/history)
 */
public record WatchDisplayOptions(boolean headers, boolean jsonEnvelope, boolean textBlock, boolean annotations) {
    /**
     * Creates options; missing headers/annotations default to on, the others to off.
     *
     * @param headers      headers
     * @param jsonEnvelope json envelope
     * @param textBlock    text block
     * @param annotations  annotations
     * @return options
     */
    @JsonCreator
    public static WatchDisplayOptions of(@JsonProperty("headers") Boolean headers,
                                         @JsonProperty("jsonEnvelope") Boolean jsonEnvelope,
                                         @JsonProperty("textBlock") Boolean textBlock,
                                         @JsonProperty("annotations") Boolean annotations) {
        return new WatchDisplayOptions(headers == null || headers, Boolean.TRUE.equals(jsonEnvelope),
                Boolean.TRUE.equals(textBlock), annotations == null || annotations);
    }

    /**
     * Returns headers + annotations, the defaults.
     *
     * @return defaults
     */
    public static WatchDisplayOptions defaults() {
        return new WatchDisplayOptions(true, false, false, true);
    }
}
