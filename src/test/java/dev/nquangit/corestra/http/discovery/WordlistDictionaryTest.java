package dev.nquangit.corestra.http.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static dev.nquangit.corestra.http.discovery.WordlistDictionary.Category.*;
import static org.junit.jupiter.api.Assertions.*;

class WordlistDictionaryTest {
    @TempDir Path directory;
    @Test void uniqueWordsPreserveParameterCaseAndCanonicalizeHeadersAcrossHosts() {
        var words = new WordlistDictionary();
        words.observe(QUERY_PARAMETERS,"userId","API.TEST"); words.observe(QUERY_PARAMETERS,"userId","api.test");
        words.observe(QUERY_PARAMETERS,"UserId","api.test"); words.observe(QUERY_PARAMETERS,"userId","other.test");
        words.observe(REQUEST_HEADERS,"X-User","api.test"); words.observe(REQUEST_HEADERS,"x-user","api.test");
        assertEquals(4, words.snapshot().size());
        var groups = WordlistDictionary.grouped(words.snapshot(),null,"","");
        assertEquals(List.of("UserId","userId"),groups.get(QUERY_PARAMETERS)); assertEquals(List.of("x-user"),groups.get(REQUEST_HEADERS));
        assertEquals(List.of("userId"),WordlistDictionary.grouped(words.snapshot(),QUERY_PARAMETERS,"other.test","ID").get(QUERY_PARAMETERS));
        long revision = words.revision(); words.observe(REQUEST_HEADERS,"X-User","api.test"); assertEquals(revision,words.revision());
    }
    @Test void memoryIsBoundedAndRemovalCanBeRestrictedToOneHost() {
        var words = new WordlistDictionary(2); words.observe(PATH_SEGMENTS,"api","a.test");words.observe(PATH_SEGMENTS,"api","b.test");words.observe(PATH_SEGMENTS,"users","a.test");
        assertEquals(1,words.evicted()); assertEquals(2,words.snapshot().size());
        words.remove(words.snapshot().stream().filter(e->e.host().equals("a.test")).toList());
        assertEquals("b.test",words.snapshot().getFirst().host()); words.clear();assertTrue(words.snapshot().isEmpty());assertEquals(0,words.evicted());
        words.observe(QUERY_PARAMETERS,"bad\nname","a.test");assertTrue(words.snapshot().isEmpty());
    }
    @Test void txtAndZipAreUtf8SortedDeduplicatedAndHaveSeparateFiles() throws Exception {
        Path text = directory.resolve("names.txt"); WordlistExporter.text(text,List.of("zeta","alpha","alpha","tên"));
        assertEquals("alpha\ntên\nzeta\n",Files.readString(text));
        Path zip = directory.resolve("words.zip");WordlistExporter.zip(zip,Map.of(QUERY_PARAMETERS,List.of("id","name","id"),REQUEST_HEADERS,List.of("x-user")));
        try(var archive=new ZipFile(zip.toFile(),StandardCharsets.UTF_8)) {
            assertEquals(2,archive.size()); assertEquals("id\nname\n",new String(archive.getInputStream(archive.getEntry("query-parameters.txt")).readAllBytes(),StandardCharsets.UTF_8));
            assertNotNull(archive.getEntry("request-headers.txt"));
        }
        assertThrows(IllegalArgumentException.class,()->WordlistExporter.text(text,List.of("bad\nline")));
        assertEquals("alpha\ntên\nzeta\n",Files.readString(text));
        try(var files=Files.list(directory)){assertEquals(2,files.count());}
    }
}
