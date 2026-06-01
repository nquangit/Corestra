package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ProjectMetadata {
    private final String name;
    private final String description;
    private final String owner;
    private final String note;
    private final long createdAtEpochMillis;
    private final long updatedAtEpochMillis;

    @JsonCreator
    public ProjectMetadata(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("owner") String owner,
            @JsonProperty("note") String note,
            @JsonProperty("createdAtEpochMillis") long createdAtEpochMillis,
            @JsonProperty("updatedAtEpochMillis") long updatedAtEpochMillis) {
        this.name = normalize(name);
        this.description = normalize(description);
        this.owner = normalize(owner);
        this.note = normalize(note);
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        this.updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
    }

    public static ProjectMetadata createNew() {
        long now = System.currentTimeMillis();
        return new ProjectMetadata("", "", "", "", now, now);
    }

    public String name() { return name; }
    public String description() { return description; }
    public String owner() { return owner; }
    public String note() { return note; }
    public long createdAtEpochMillis() { return createdAtEpochMillis; }
    public long updatedAtEpochMillis() { return updatedAtEpochMillis; }

    public ProjectMetadata withName(String name) {
        return new ProjectMetadata(name, description, owner, note, createdAtEpochMillis, updatedAtEpochMillis);
    }

    public ProjectMetadata withDescription(String description) {
        return new ProjectMetadata(name, description, owner, note, createdAtEpochMillis, updatedAtEpochMillis);
    }

    public ProjectMetadata withOwner(String owner) {
        return new ProjectMetadata(name, description, owner, note, createdAtEpochMillis, updatedAtEpochMillis);
    }

    public ProjectMetadata withNote(String note) {
        return new ProjectMetadata(name, description, owner, note, createdAtEpochMillis, updatedAtEpochMillis);
    }

    public ProjectMetadata withUpdatedAtEpochMillis(long millis) {
        return new ProjectMetadata(name, description, owner, note, createdAtEpochMillis, millis);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProjectMetadata that)) return false;
        return createdAtEpochMillis == that.createdAtEpochMillis
                && updatedAtEpochMillis == that.updatedAtEpochMillis
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(owner, that.owner)
                && Objects.equals(note, that.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, owner, note, createdAtEpochMillis, updatedAtEpochMillis);
    }

    @Override
    public String toString() {
        return "ProjectMetadata{name='" + name + "', owner='" + owner + "'}";
    }

    private static String normalize(String v) { return v == null ? "" : v; }
}
