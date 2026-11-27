package dev.nquangit.corestra.integration.watch;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.responses.HttpResponse;

/**
 * Thin Montoya adapter applying a {@link WatchResponseRenderer.Decoration} to a response and its
 * annotations. Headers replace same-named headers; {@code withBody} keeps Content-Length correct;
 * notes are appended to existing notes; the highlight is set only when the decoration has one.
 */
public final class WatchResponseDecorator {
    private WatchResponseDecorator() { }

    /**
     * Applies the decoration.
     *
     * @param decoration decoration
     * @param response   response to decorate (e.g. the auto-trigger delegate's aggregated response)
     * @param existing   current annotations, or null
     * @return action carrying the decorated response and merged annotations
     */
    public static ResponseReceivedAction apply(WatchResponseRenderer.Decoration decoration, HttpResponse response,
                                               Annotations existing) {
        HttpResponse out = response;
        for (WatchResponseRenderer.Header header : decoration.headers()) {
            out = out.hasHeader(header.name()) ? out.withUpdatedHeader(header.name(), header.value())
                    : out.withAddedHeader(header.name(), header.value());
        }
        if (decoration.body() != null) out = out.withBody(decoration.body());
        if (decoration.notes() == null && decoration.highlight() == null) {
            return existing == null ? ResponseReceivedAction.continueWith(out)
                    : ResponseReceivedAction.continueWith(out, existing);
        }
        Annotations annotations = existing != null ? existing : Annotations.annotations();
        if (decoration.notes() != null) {
            String current = annotations.hasNotes() ? annotations.notes() : null;
            annotations = annotations.withNotes(WatchResponseRenderer.mergeNotes(current, decoration.notes()));
        }
        if (decoration.highlight() != null) {
            annotations = annotations.withHighlightColor(HighlightColor.valueOf(decoration.highlight().name()));
        }
        return ResponseReceivedAction.continueWith(out, annotations);
    }
}
