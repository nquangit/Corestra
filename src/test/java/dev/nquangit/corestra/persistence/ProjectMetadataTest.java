package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMetadataTest {

    @Test
    void createNewHasEmptyTextFieldsAndPositiveTimestamps() {
        ProjectMetadata meta = ProjectMetadata.createNew();
        assertEquals("", meta.name());
        assertEquals("", meta.description());
        assertEquals("", meta.owner());
        assertEquals("", meta.note());
        assertTrue(meta.createdAtEpochMillis() > 0);
        assertEquals(meta.createdAtEpochMillis(), meta.updatedAtEpochMillis());
    }

    @Test
    void withNamePreservesOtherFields() {
        ProjectMetadata meta = ProjectMetadata.createNew().withName("Banking");
        assertEquals("Banking", meta.name());
        assertEquals("", meta.description());
    }

    @Test
    void withDescriptionPreservesOtherFields() {
        ProjectMetadata meta = ProjectMetadata.createNew().withDescription("desc");
        assertEquals("desc", meta.description());
        assertEquals("", meta.name());
    }

    @Test
    void withOwnerPreservesOtherFields() {
        ProjectMetadata meta = ProjectMetadata.createNew().withOwner("alice");
        assertEquals("alice", meta.owner());
    }

    @Test
    void withNotePreservesOtherFields() {
        ProjectMetadata meta = ProjectMetadata.createNew().withNote("notes here");
        assertEquals("notes here", meta.note());
    }

    @Test
    void withUpdatedAtPreservesCreatedAt() {
        ProjectMetadata meta = new ProjectMetadata("n", "d", "o", "nt", 1000L, 1000L);
        ProjectMetadata updated = meta.withUpdatedAtEpochMillis(9999L);
        assertEquals(1000L, updated.createdAtEpochMillis());
        assertEquals(9999L, updated.updatedAtEpochMillis());
    }

    @Test
    void nullFieldsNormalizedToEmptyString() {
        ProjectMetadata meta = new ProjectMetadata(null, null, null, null, 1000L, 1000L);
        assertEquals("", meta.name());
        assertEquals("", meta.description());
        assertEquals("", meta.owner());
        assertEquals("", meta.note());
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProjectMetadata original = new ProjectMetadata("proj", "desc", "bob", "note", 1000L, 2000L);
        String json = mapper.writeValueAsString(original);
        ProjectMetadata restored = mapper.readValue(json, ProjectMetadata.class);
        assertEquals(original, restored);
    }

    @Test
    void jacksonDeserializesLegacyJsonWithMissingFieldsAsEmpty() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"createdAtEpochMillis\":500,\"updatedAtEpochMillis\":600}";
        ProjectMetadata meta = mapper.readValue(json, ProjectMetadata.class);
        assertEquals("", meta.name());
        assertEquals("", meta.owner());
        assertEquals(500L, meta.createdAtEpochMillis());
    }
}
