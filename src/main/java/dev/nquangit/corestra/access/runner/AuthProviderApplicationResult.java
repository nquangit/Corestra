package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.List;
import java.util.Objects;

final class AuthProviderApplicationResult {
    private final StoredRequestTemplate request;
    private final List<String> warnings;

    AuthProviderApplicationResult(StoredRequestTemplate request, List<String> warnings) {
        this.request = Objects.requireNonNull(request, "request");
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    StoredRequestTemplate request() {
        return request;
    }

    List<String> warnings() {
        return warnings;
    }
}
