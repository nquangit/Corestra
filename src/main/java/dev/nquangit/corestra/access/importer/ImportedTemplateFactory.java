package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateAnalyzer;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.RequestTemplateHasher;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.Objects;
import java.util.Set;

final class ImportedTemplateFactory {
    private final AccessIdGenerator idGenerator;
    private final AccessRequestTemplateAnalyzer analyzer;
    private final RequestTemplateHasher hasher;

    ImportedTemplateFactory(
            AccessIdGenerator idGenerator,
            AccessRequestTemplateAnalyzer analyzer,
            RequestTemplateHasher hasher) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    AccessRequestTemplate create(
            String name,
            StoredRequestTemplate request,
            OperationMetadata metadata,
            boolean enabled) {
        String id = idGenerator.newId("tmpl");
        String hash = hasher.hash(request);
        AccessRequestTemplate empty = new AccessRequestTemplate(
                id,
                normalizeName(name),
                request,
                Set.of(),
                metadata,
                "",
                "",
                hash,
                enabled);
        Set<ScopedVariableRef> placeholders = analyzer.discoverPlaceholders(empty, RenderMode.STRICT);
        return new AccessRequestTemplate(
                id,
                normalizeName(name),
                request,
                placeholders,
                metadata,
                "",
                "",
                hash,
                enabled);
    }

    private static String normalizeName(String name) {
        String normalized = Objects.requireNonNullElse(name, "").strip();
        return normalized.isBlank() ? "Imported request" : normalized;
    }
}
