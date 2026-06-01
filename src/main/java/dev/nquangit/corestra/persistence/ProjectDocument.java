package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.List;
import java.util.Objects;

public final class ProjectDocument {
    private final int schemaVersion;
    private final ProjectMetadata metadata;
    private final List<FlowDefinition> flows;

    public ProjectDocument(int schemaVersion, ProjectMetadata metadata, List<FlowDefinition> flows) {
        this.schemaVersion = schemaVersion;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.flows = List.copyOf(Objects.requireNonNull(flows, "flows"));
    }

    public int schemaVersion() { return schemaVersion; }
    public ProjectMetadata metadata() { return metadata; }
    public List<FlowDefinition> flows() { return flows; }
}
