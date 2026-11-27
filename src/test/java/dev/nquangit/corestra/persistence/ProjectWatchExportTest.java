package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.watch.WatchDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.nquangit.corestra.watch.WatchConfigFixtures.fullWatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWatchExportTest {
    private final ProjectSerializer serializer = new ProjectSerializer(new FlowSerializer());
    private final ProjectMetadata meta = new ProjectMetadata("P", "", "", "", 1L, 2L);

    @Test
    void fullExportRoundTripsWatchesAndOtherSubsystemsSurviveWithWatches() {
        WatchDefinition watch = fullWatch("w1");
        ProjectDocument doc = new ProjectDocument(1, meta, List.of()).withWatches(List.of(watch));
        assertEquals(List.of(watch), doc.withManualFlows(List.of()).watches(), "withManualFlows keeps watches");

        ProjectDocument restored = serializer.deserializeProject(serializer.serializeProject(doc, ExportMode.FULL_EXPORT));

        assertEquals(List.of(watch), restored.watches());
    }

    @Test
    void sanitizedExportRedactsProbeCredentialsButKeepsStructure() {
        WatchDefinition watch = fullWatch("w1");
        ProjectDocument doc = new ProjectDocument(1, meta, List.of()).withWatches(List.of(watch));

        String json = serializer.serializeProject(doc, ExportMode.SANITIZED_EXPORT);
        WatchDefinition restored = serializer.deserializeProject(json).watches().getFirst();

        assertFalse(json.contains("sid=abc"));
        assertTrue(restored.probe().request().headerLines().contains("Accept: application/json"));
        assertEquals(watch.observations(), restored.observations());
        assertEquals(watch.expectations(), restored.expectations());
        assertEquals(watch.requestVariables(), restored.requestVariables());
        assertEquals(watch.probe().inheritHeaders(), restored.probe().inheritHeaders());

        String excluded = serializer.serializeProject(doc, ExportMode.EXCLUDE_SECRETS);
        assertFalse(excluded.contains("sid=abc"));
        assertFalse(excluded.contains("<redacted>"));
    }

    @Test
    void olderProjectFilesWithoutWatchesStillImport() {
        String json = serializer.serializeProject(new ProjectDocument(1, meta, List.of()))
                .replace(",\"watches\":[]", "");
        assertFalse(json.contains("watches"));
        assertTrue(serializer.deserializeProject(json).watches().isEmpty());
    }

    @Test
    void malformedWatchesAreRejected() {
        String json = serializer.serializeProject(new ProjectDocument(1, meta, List.of()))
                .replace("\"watches\":[]", "\"watches\":{}");
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(json));
    }
}
