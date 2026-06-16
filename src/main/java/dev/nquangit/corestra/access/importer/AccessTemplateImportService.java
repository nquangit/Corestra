package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.id.AccessIdGenerator;

import java.util.List;
import java.util.Objects;

/**
 * Dispatches access template imports to the importer matching the selected format.
 */
public final class AccessTemplateImportService implements AccessTemplateImporter {
    private final OpenApiAccessTemplateImporter openApiImporter;
    private final PostmanAccessTemplateImporter postmanImporter;

    /**
     * Creates an import service.
     *
     * @param idGenerator template ID generator
     */
    public AccessTemplateImportService(AccessIdGenerator idGenerator) {
        this(new OpenApiAccessTemplateImporter(idGenerator), new PostmanAccessTemplateImporter(idGenerator));
    }

    /**
     * Creates an import service.
     *
     * @param openApiImporter OpenAPI importer
     * @param postmanImporter Postman importer
     */
    public AccessTemplateImportService(
            OpenApiAccessTemplateImporter openApiImporter,
            PostmanAccessTemplateImporter postmanImporter) {
        this.openApiImporter = Objects.requireNonNull(openApiImporter, "openApiImporter");
        this.postmanImporter = Objects.requireNonNull(postmanImporter, "postmanImporter");
    }

    /**
     * Imports templates using the selected or auto-detected source format.
     *
     * @param source import source
     * @param options import options
     * @return import result
     */
    @Override
    public ImportResult importTemplates(ImportSource source, ImportOptions options) {
        Objects.requireNonNull(source, "source");
        ImportFormat format = source.format() == ImportFormat.AUTO_DETECT
                ? detectFormat(source.content())
                : source.format();
        ImportSource resolvedSource = new ImportSource(source.name(), source.content(), format);
        return switch (format) {
            case OPENAPI_JSON, SWAGGER_JSON -> openApiImporter.importTemplates(resolvedSource, options);
            case POSTMAN_COLLECTION_JSON -> postmanImporter.importTemplates(resolvedSource, options);
            case OPENAPI_YAML -> new ImportResult(
                    List.of(),
                    List.of(),
                    List.of(new ImportError(source.name(), "OpenAPI YAML import is deferred in PR 22; use JSON input.")),
                    1);
            case AUTO_DETECT -> new ImportResult(
                    List.of(),
                    List.of(),
                    List.of(new ImportError(source.name(), "Could not auto-detect import format.")),
                    1);
        };
    }

    private ImportFormat detectFormat(String content) {
        String text = content == null ? "" : content.strip();
        if (text.contains("\"item\"") && text.contains("\"request\"")) {
            return ImportFormat.POSTMAN_COLLECTION_JSON;
        }
        if (text.contains("\"swagger\"")) {
            return ImportFormat.SWAGGER_JSON;
        }
        if (text.contains("\"openapi\"")) {
            return ImportFormat.OPENAPI_JSON;
        }
        if (text.startsWith("openapi:") || text.contains("\nopenapi:")) {
            return ImportFormat.OPENAPI_YAML;
        }
        return ImportFormat.AUTO_DETECT;
    }
}
