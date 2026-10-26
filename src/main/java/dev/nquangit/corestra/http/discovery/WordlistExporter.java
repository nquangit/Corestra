package dev.nquangit.corestra.http.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** UTF-8, one-word-per-line exports with atomic replacement and fixed ZIP entry names. */
public final class WordlistExporter {
    private WordlistExporter() { }
    /** Exports one sorted, unique plain-text list. */
    public static void text(Path destination, Collection<String> words) throws IOException {
        write(destination, temp -> Files.writeString(temp, text(words), StandardCharsets.UTF_8));
    }
    /** Exports separate category files inside a ZIP, without host metadata or HTTP messages. */
    public static void zip(Path destination, Map<WordlistDictionary.Category, List<String>> groups) throws IOException {
        write(destination, temp -> {
            try (var output = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                for (var category : WordlistDictionary.Category.values()) {
                    List<String> words = groups.getOrDefault(category, List.of()); if (words.isEmpty()) continue;
                    output.putNextEntry(new ZipEntry(category.fileName())); output.write(text(words).getBytes(StandardCharsets.UTF_8)); output.closeEntry();
                }
            }
        });
    }
    private static String text(Collection<String> words) {
        var sorted = new TreeSet<String>();
        for (String word : words) {
            if (word == null || word.isBlank() || word.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Wordlists require non-empty single-line words");
            sorted.add(word);
        }
        return sorted.isEmpty() ? "" : String.join("\n", sorted) + "\n";
    }
    private interface Writer { void write(Path path) throws IOException; }
    private static void write(Path destination, Writer writer) throws IOException {
        Path absolute = destination.toAbsolutePath(); Path temp = Files.createTempFile(absolute.getParent(), ".corestra-wordlist-", ".tmp");
        try {
            writer.write(temp);
            try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
