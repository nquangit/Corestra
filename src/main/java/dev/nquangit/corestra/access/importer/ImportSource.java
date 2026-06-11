package dev.nquangit.corestra.access.importer;

import java.util.Objects;

/**
 * In-memory source document for access template import.
 */
public final class ImportSource {
    private final String name;
    private final String content;
    private final ImportFormat format;

    /**
     * Creates an import source.
     *
     * @param name display name or filename
     * @param content source document content
     * @param format declared format
     */
    public ImportSource(String name, String content, ImportFormat format) {
        this.name = name == null || name.isBlank() ? "import" : name.strip();
        this.content = Objects.requireNonNullElse(content, "");
        this.format = Objects.requireNonNullElse(format, ImportFormat.AUTO_DETECT);
    }

    /**
     * Returns the source name.
     *
     * @return source name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the raw source content.
     *
     * @return raw source content
     */
    public String content() {
        return content;
    }

    /**
     * Returns the declared source format.
     *
     * @return source format
     */
    public ImportFormat format() {
        return format;
    }
}
