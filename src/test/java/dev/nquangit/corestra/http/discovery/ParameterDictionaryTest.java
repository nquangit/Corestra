package dev.nquangit.corestra.http.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterDictionaryTest {
    @Test
    void aggregatesWithinHostPathAndLocationAndEvictsOldest() {
        ParameterDictionary dictionary = new ParameterDictionary(2);
        dictionary.observe("EXAMPLE.test", "/one?a=1", ParameterDictionary.Location.QUERY, "Token", "");
        dictionary.observe("example.test", "/one?a=2", ParameterDictionary.Location.QUERY, "token", "sample");
        dictionary.observe("example.test", "/two", ParameterDictionary.Location.JSON, "amount", "10");
        assertEquals(2, dictionary.snapshot().size());
        assertEquals(2, dictionary.snapshot().get(1).count());

        dictionary.observe("other.test", "/three", ParameterDictionary.Location.FORM, "name", "A");
        assertEquals(2, dictionary.snapshot().size());
        assertEquals("name", dictionary.snapshot().getFirst().name());
    }
}
