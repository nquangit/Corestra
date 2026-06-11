package dev.nquangit.corestra.access.importer;

/**
 * Imports external API definitions into access request templates.
 */
public interface AccessTemplateImporter {
    /**
     * Imports access request templates from one source document.
     *
     * @param source import source
     * @param options import options
     * @return import result containing preview templates and diagnostics
     */
    ImportResult importTemplates(ImportSource source, ImportOptions options);
}
