package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.template.RenderedAccessRequest;

import java.util.Objects;

final class AccessPreview {
    private final RenderedAccessRequest renderedRequest;
    private final String originalSummary;
    private final String renderedSummary;

    AccessPreview(RenderedAccessRequest renderedRequest, String originalSummary, String renderedSummary) {
        this.renderedRequest = Objects.requireNonNull(renderedRequest, "renderedRequest");
        this.originalSummary = Objects.requireNonNull(originalSummary, "originalSummary");
        this.renderedSummary = Objects.requireNonNull(renderedSummary, "renderedSummary");
    }

    RenderedAccessRequest renderedRequest() {
        return renderedRequest;
    }

    String originalSummary() {
        return originalSummary;
    }

    String renderedSummary() {
        return renderedSummary;
    }
}
