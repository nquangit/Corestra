package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Inspects and removes project-scoped data owned by this extension. */
public final class ProjectDataMaintenanceService {
    private final PersistedObject extensionData;

    /** Creates an in-memory service for standalone UI construction and tests. */
    public ProjectDataMaintenanceService() {
        this(emptyPersistedObject());
    }

    /** Creates a service backed by Burp project extension data. */
    public ProjectDataMaintenanceService(Persistence persistence) {
        this(Objects.requireNonNull(persistence, "persistence").extensionData());
    }

    ProjectDataMaintenanceService(PersistedObject extensionData) {
        this.extensionData = Objects.requireNonNull(extensionData, "extensionData");
    }

    /**
     * Returns the exact UTF-8 size of stored strings plus a count of all persisted entries.
     * Corestra currently stores its project documents as strings.
     */
    public StorageUsage storageUsage() {
        long stringBytes = 0L;
        int entries = 0;
        for (String key : keys(extensionData::stringKeys)) {
            entries++;
            try {
                String value = extensionData.getString(key);
                if (value != null) stringBytes += value.getBytes(StandardCharsets.UTF_8).length;
            } catch (RuntimeException ignored) {
                // The usage label must not interfere with the rest of the Project UI.
            }
        }
        entries += count(extensionData::booleanKeys);
        entries += count(extensionData::byteKeys);
        entries += count(extensionData::shortKeys);
        entries += count(extensionData::integerKeys);
        entries += count(extensionData::longKeys);
        entries += count(extensionData::byteArrayKeys);
        entries += count(extensionData::httpRequestKeys);
        entries += count(extensionData::httpRequestListKeys);
        entries += count(extensionData::httpResponseKeys);
        entries += count(extensionData::httpResponseListKeys);
        entries += count(extensionData::httpRequestResponseKeys);
        entries += count(extensionData::httpRequestResponseListKeys);
        entries += count(extensionData::booleanListKeys);
        entries += count(extensionData::shortListKeys);
        entries += count(extensionData::integerListKeys);
        entries += count(extensionData::longListKeys);
        entries += count(extensionData::stringListKeys);
        entries += count(extensionData::byteArrayListKeys);
        entries += count(extensionData::childObjectKeys);
        return new StorageUsage(stringBytes, entries);
    }

    /** Deletes every project-scoped entry owned by this extension, including quarantine data. */
    public void clearAllProjectData() {
        delete(extensionData::stringKeys, extensionData::deleteString);
        delete(extensionData::booleanKeys, extensionData::deleteBoolean);
        delete(extensionData::byteKeys, extensionData::deleteByte);
        delete(extensionData::shortKeys, extensionData::deleteShort);
        delete(extensionData::integerKeys, extensionData::deleteInteger);
        delete(extensionData::longKeys, extensionData::deleteLong);
        delete(extensionData::byteArrayKeys, extensionData::deleteByteArray);
        delete(extensionData::httpRequestKeys, extensionData::deleteHttpRequest);
        delete(extensionData::httpRequestListKeys, extensionData::deleteHttpRequestList);
        delete(extensionData::httpResponseKeys, extensionData::deleteHttpResponse);
        delete(extensionData::httpResponseListKeys, extensionData::deleteHttpResponseList);
        delete(extensionData::httpRequestResponseKeys, extensionData::deleteHttpRequestResponse);
        delete(extensionData::httpRequestResponseListKeys, extensionData::deleteHttpRequestResponseList);
        delete(extensionData::booleanListKeys, extensionData::deleteBooleanList);
        delete(extensionData::shortListKeys, extensionData::deleteShortList);
        delete(extensionData::integerListKeys, extensionData::deleteIntegerList);
        delete(extensionData::longListKeys, extensionData::deleteLongList);
        delete(extensionData::stringListKeys, extensionData::deleteStringList);
        delete(extensionData::byteArrayListKeys, extensionData::deleteByteArrayList);
        delete(extensionData::childObjectKeys, extensionData::deleteChildObject);
    }

    /** Formats bytes for a compact UI label. */
    public static String formatBytes(long bytes) {
        long safe = Math.max(0L, bytes);
        if (safe < 1024L) return safe + " B";
        if (safe < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KiB", safe / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MiB", safe / (1024.0 * 1024.0));
    }

    private static int count(Supplier<Set<String>> supplier) {
        return keys(supplier).size();
    }

    private static List<String> keys(Supplier<Set<String>> supplier) {
        try {
            Set<String> values = supplier.get();
            return values == null ? List.of() : new ArrayList<>(values);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static void delete(Supplier<Set<String>> keys, Consumer<String> deletion) {
        for (String key : keys(keys)) {
            try {
                deletion.accept(key);
            } catch (RuntimeException ignored) {
                // Continue so one malformed entry cannot prevent the remaining cleanup.
            }
        }
    }

    private static PersistedObject emptyPersistedObject() {
        return (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(), new Class<?>[]{PersistedObject.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.endsWith("Keys")) return Set.of();
                    if (name.startsWith("delete") || name.startsWith("set")) return null;
                    return switch (name) {
                        case "toString" -> "EmptyCorestraProjectData";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                });
    }

    /** Storage usage visible to the Project UI. */
    public record StorageUsage(long serializedStringBytes, int entryCount) {
        public StorageUsage {
            if (serializedStringBytes < 0L || entryCount < 0) {
                throw new IllegalArgumentException("storage usage values must not be negative");
            }
        }
    }
}
