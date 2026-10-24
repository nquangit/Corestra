package dev.nquangit.corestra.manual;

import burp.api.montoya.persistence.*;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.persistence.*;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.access.security.ExportMode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualPersistenceTest {
    @Test void independentStorageAndQuarantineNeverOverwriteTheFlowiseDocument(){
        Map<String,String> data=new HashMap<>();
        var stored=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){
            case "getString"->data.get(a[0]);case "setString"->{data.put((String)a[0],(String)a[1]);yield null;}case "deleteString"->{data.remove(a[0]);yield null;}default->null;});
        var persistence=proxy(Persistence.class,(p,m,a)->stored);
        var serializer=new FlowSerializer();var migration=new PersistenceMigrationService();
        var flows=new FlowRepository(persistence,serializer,migration);var drafts=new FlowRepository(persistence,serializer,migration,"corestra.manual.flows.document");
        var f=ManualTemplatesTest.flow("source",ManualTemplatesTest.step("one","/","",List.of()));flows.saveAll(List.of(f));
        var copy=new ManualFlowImporter().copy(f,List.of(f)).flows();drafts.saveAll(copy);
        assertEquals(List.of(f),flows.loadAll());assertEquals(copy,new FlowRepository(persistence,serializer,migration,"corestra.manual.flows.document").loadAll());
        data.put("corestra.manual.flows.document","invalid-json");
        var recovered=new FlowRepository(persistence,serializer,migration,"corestra.manual.flows.document");assertTrue(recovered.loadAll().isEmpty());
        assertEquals("invalid-json",data.get("corestra.manual.flows.document.quarantine"));assertEquals(List.of(f),flows.loadAll());
    }
    @Test void projectRoundTripAndLegacyDefaultsPreserveSecretExportModes(){
        var serializer=new ProjectSerializer(new FlowSerializer());
        var step=ManualTemplatesTest.step("one","/","",List.of()).withRequestTemplate(new StoredRequestTemplate("GET","https","api.test",443,"/",List.of("Authorization: Bearer very-private-token"),"{{value}}"));
        var draft=ManualTemplatesTest.flow("manual",step);
        var doc=new ProjectDocument(1,ProjectMetadata.createNew(),ProjectSettings.defaults(),List.of(),AccessWorkspace.empty(),List.of()).withManualFlows(List.of(draft));
        String full=serializer.serializeProject(doc,ExportMode.FULL_EXPORT);assertEquals(List.of(draft),serializer.deserializeProject(full).manualFlows());
        assertFalse(serializer.serializeProject(doc,ExportMode.SANITIZED_EXPORT).contains("very-private-token"));
        assertFalse(serializer.serializeProject(doc,ExportMode.EXCLUDE_SECRETS).contains("very-private-token"));
        String legacy=serializer.serializeProject(new ProjectDocument(1,ProjectMetadata.createNew(),ProjectSettings.defaults(),List.of(),AccessWorkspace.empty(),List.of()),ExportMode.FULL_EXPORT);
        try {var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(legacy);((com.fasterxml.jackson.databind.node.ObjectNode)json).remove("manualFlows");assertTrue(serializer.deserializeProject(json.toString()).manualFlows().isEmpty());}
        catch(Exception e){throw new AssertionError(e);}
    }
    @Test void malformedOrDuplicateDraftsAreRejectedBeforeImportCanChangeWorkspace() throws Exception {
        var serializer = new ProjectSerializer(new FlowSerializer());
        var f = ManualTemplatesTest.flow("same", ManualTemplatesTest.step("one", "/", "", List.of()));
        var doc = new ProjectDocument(1, ProjectMetadata.createNew(), ProjectSettings.defaults(), List.of(), AccessWorkspace.empty(), List.of()).withManualFlows(List.of(f));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(serializer.serializeProject(doc, ExportMode.FULL_EXPORT));
        var drafts = (com.fasterxml.jackson.databind.node.ArrayNode)tree.get("manualFlows"); drafts.add(drafts.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(tree.toString()));
        tree.put("manualFlows", "invalid");
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(tree.toString()));
    }

}
