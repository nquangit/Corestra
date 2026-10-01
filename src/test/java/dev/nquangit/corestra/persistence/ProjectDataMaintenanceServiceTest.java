package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.PersistedObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDataMaintenanceServiceTest {
    @Test
    void reportsSerializedStringSizeAndClearsEveryEntryType() {
        PersistedObject data = fakePersistedObject();
        data.setString("corestra.flows", "xin chào");
        data.setBoolean("corestra.flag", true);
        data.setLong("corestra.counter", 7L);
        data.setChildObject("corestra.child", fakePersistedObject());
        ProjectDataMaintenanceService service = new ProjectDataMaintenanceService(data);

        ProjectDataMaintenanceService.StorageUsage before = service.storageUsage();
        service.clearAllProjectData();
        ProjectDataMaintenanceService.StorageUsage after = service.storageUsage();

        assertEquals("xin chào".getBytes(StandardCharsets.UTF_8).length, before.serializedStringBytes());
        assertEquals(4, before.entryCount());
        assertEquals(0L, after.serializedStringBytes());
        assertEquals(0, after.entryCount());
        assertTrue(data.stringKeys().isEmpty());
        assertTrue(data.booleanKeys().isEmpty());
        assertTrue(data.longKeys().isEmpty());
        assertTrue(data.childObjectKeys().isEmpty());
    }

    @Test
    void formatsStorageSizeForProjectUi() {
        assertEquals("512 B", ProjectDataMaintenanceService.formatBytes(512));
        assertEquals("1.5 KiB", ProjectDataMaintenanceService.formatBytes(1_536));
        assertEquals("2.00 MiB", ProjectDataMaintenanceService.formatBytes(2L * 1024L * 1024L));
    }

    private static PersistedObject fakePersistedObject() {
        Map<String, String> strings = new HashMap<>();
        Set<String> booleans = new HashSet<>();
        Set<String> longs = new HashSet<>();
        Set<String> children = new HashSet<>();
        return (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(), new Class<?>[]{PersistedObject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> { strings.put((String) args[0], (String) args[1]); yield null; }
                    case "getString" -> strings.get((String) args[0]);
                    case "deleteString" -> { strings.remove((String) args[0]); yield null; }
                    case "stringKeys" -> Set.copyOf(strings.keySet());
                    case "setBoolean" -> { booleans.add((String) args[0]); yield null; }
                    case "deleteBoolean" -> { booleans.remove((String) args[0]); yield null; }
                    case "booleanKeys" -> Set.copyOf(booleans);
                    case "setLong" -> { longs.add((String) args[0]); yield null; }
                    case "deleteLong" -> { longs.remove((String) args[0]); yield null; }
                    case "longKeys" -> Set.copyOf(longs);
                    case "setChildObject" -> { children.add((String) args[0]); yield null; }
                    case "deleteChildObject" -> { children.remove((String) args[0]); yield null; }
                    case "childObjectKeys" -> Set.copyOf(children);
                    case "toString" -> "FakePersistedObject";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getName().endsWith("Keys") ? Set.of() : null;
                });
    }
}
