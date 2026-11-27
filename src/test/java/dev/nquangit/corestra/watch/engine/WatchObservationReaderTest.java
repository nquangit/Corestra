package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.watch.WatchNumberFormat;
import dev.nquangit.corestra.watch.WatchObservation;
import dev.nquangit.corestra.watch.WatchObservation.Source;
import dev.nquangit.corestra.watch.WatchObservation.ValueType;
import dev.nquangit.corestra.watch.WatchSnapshot.Value;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static dev.nquangit.corestra.watch.engine.WatchFixtures.bankAccounts;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.jsonCollection;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.jsonSelector;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.ok;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.regex;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.regexCollection;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.vndHtml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchObservationReaderTest {
    private final WatchObservationReader reader = new WatchObservationReader(new WatchNumberParser());

    private static void assertNumber(String expected, Value value) {
        assertEquals(0, new BigDecimal(expected).compareTo(value.number()),
                () -> "expected " + expected + " but was " + value);
    }

    @Test
    void jsonCollectionReadsMultiAccountBankResponse() {
        WatchObservationReader.Reading reading = reader.read(ok(bankAccounts("15000000.50", "2000000")),
                List.of(jsonCollection("balance", "$.accounts", "accountNo", "balance.available"),
                        jsonSelector("tier", "customer.tier", ValueType.TEXT)));
        assertEquals(List.of("balance[0123]", "balance[0456]", "tier"), List.copyOf(reading.values().keySet()));
        assertNumber("15000000.50", reading.values().get("balance[0123]"));
        assertEquals("15000000.50", reading.values().get("balance[0123]").raw());
        assertNumber("2000000", reading.values().get("balance[0456]"));
        assertEquals(new Value("GOLD", null), reading.values().get("tier"));
        assertTrue(reading.errors().isEmpty(), reading.errors()::toString);
    }

    @Test
    void jsonCollectionAcceptsWildcardFilterAndDotItemsPath() {
        String body = bankAccounts("1", "2");
        assertEquals(2, reader.read(ok(body), List.of(jsonCollection("b", "$.accounts[*]", "accountNo", "balance.ledger")))
                .values().size());
        assertEquals(List.of("b[0456]"), List.copyOf(reader.read(ok(body),
                List.of(jsonCollection("b", "$.accounts[?(@.type=='SAVINGS')]", "accountNo", "balance.ledger")))
                .values().keySet()));
        assertEquals(2, reader.read(ok(body), List.of(jsonCollection("b", "accounts", "accountNo", "balance.ledger")))
                .values().size());
        Map<String, Value> rootArray = reader.read(ok("[{\"id\":7,\"v\":[10,\"1.234,5\"]}]"),
                List.of(jsonCollection("x", "", "id", "v.1"))).values();
        assertNumber("1234.5", rootArray.get("x[7]"));
    }

    @Test
    void jsonCollectionMinorUnitsAndStringValues() {
        WatchObservation cents = new WatchObservation("c", "cents", Source.JSON_COLLECTION, "", "$.items", "k", "v",
                ValueType.NUMBER, new WatchNumberFormat(true, ".", ",", 2, ""));
        Map<String, Value> values = reader.read(ok("{\"items\":[{\"k\":\"a\",\"v\":123456},{\"k\":\"b\",\"v\":\"-105\"}]}"),
                List.of(cents)).values();
        assertNumber("1234.56", values.get("cents[a]"));
        assertNumber("-1.05", values.get("cents[b]"));
    }

    @Test
    void jsonCollectionErrorsAndDuplicates() {
        String body = """
                {"accounts":[{"no":"1","bal":5},{"no":"1","bal":6},{"bal":7},{"no":"2"},{"no":"3","bal":"abc"}]}
                """;
        WatchObservationReader.Reading reading = reader.read(ok(body), List.of(jsonCollection("b", "$.accounts", "no", "bal")));
        assertEquals(List.of("b[1]", "b[3]"), List.copyOf(reading.values().keySet()));
        assertNumber("5", reading.values().get("b[1]"));
        assertNull(reading.values().get("b[3]").number());
        assertEquals("abc", reading.values().get("b[3]").raw());
        String errors = String.join("\n", reading.errors());
        assertTrue(errors.contains("b[1]: duplicate key"), errors);
        assertTrue(errors.contains("element #3 has no scalar key"), errors);
        assertTrue(errors.contains("b[2] has no value"), errors);
        assertTrue(errors.contains("b[3]: 'abc' is not a number"), errors);
    }

    @Test
    void jsonCollectionStructuralErrors() {
        assertError(reader.read(ok("not json"), List.of(jsonCollection("b", "$.a", "k", "v"))), "b: body is not valid JSON");
        assertError(reader.read(ok("{}"), List.of(jsonCollection("b", "$.a", "k", "v"))), "not found");
        assertError(reader.read(ok("{\"a\":{}}"), List.of(jsonCollection("b", "$.a", "k", "v"))), "did not select an array");
        assertError(reader.read(ok("{\"a\":[]}"), List.of(jsonCollection("b", "$.a", "k", "v"))), "matched no elements");
        assertError(reader.read(ok("{\"a\":[]}"), List.of(jsonCollection("b", "$.a[?(@.x =)]", "k", "v"))), "invalid items path");
        assertError(reader.read(ok("{\"a\":[]}"), List.of(jsonCollection("b", "$.a", "", "v"))), "required");
        assertError(reader.read(ok(""), List.of(jsonCollection("b", "$.a", "k", "v"))), "b:");
    }

    @Test
    void jsonSelectorUsesFlowiseSyntaxAndMachineNumbers() {
        String body = "{\"rate\":1.234,\"data\":{\"bal\":\"1.234.567,89\"},\"list\":[1,2]}";
        Map<String, Value> values = reader.read(ok(body), List.of(
                jsonSelector("rate", "$.rate", ValueType.NUMBER),
                jsonSelector("bal", "data.bal", ValueType.NUMBER),
                jsonSelector("first", "pointer:/list/0", ValueType.NUMBER))).values();
        assertNumber("1.234", values.get("rate"));
        assertNumber("1234567.89", values.get("bal"));
        assertNumber("1", values.get("first"));
        WatchObservationReader.Reading multi = reader.read(ok(body), List.of(jsonSelector("x", "$.list[*]", ValueType.NUMBER)));
        assertTrue(multi.values().isEmpty());
        assertError(multi, "x: JSONPath matched 2 values");
        assertError(reader.read(ok(body), List.of(jsonSelector("x", "", ValueType.NUMBER))), "selector is empty");
    }

    @Test
    void regexOnVndHtml() {
        String body = vndHtml("15.000.000", "2.000.000");
        WatchObservationReader.Reading reading = reader.read(ok(body), List.of(
                regexCollection("balance", "<td class=\"no\">(?<key>\\d+)</td><td class=\"bal\">(?<value>[^<]+)</td>",
                        new WatchNumberFormat(false, ",", ".", 0, "[^0-9.,\\-]")),
                regex("first", "class=\"bal\">([^<]+)<", ValueType.NUMBER),
                regex("named", "(\\w+) class=\"bal\">(?<value>[^<]+)<", ValueType.NUMBER),
                regex("total", "Total: (.+)</div>", ValueType.TEXT)));
        assertEquals(List.of("balance[0123]", "balance[0456]", "first", "named", "total"),
                List.copyOf(reading.values().keySet()));
        assertNumber("15000000", reading.values().get("balance[0123]"));
        assertEquals("15.000.000 VND", reading.values().get("balance[0123]").raw());
        assertNumber("2000000", reading.values().get("balance[0456]"));
        assertNumber("15000000", reading.values().get("first"));
        assertNumber("15000000", reading.values().get("named"));
        assertEquals(new Value("n/a", null), reading.values().get("total"));
        assertTrue(reading.errors().isEmpty(), reading.errors()::toString);
    }

    @Test
    void regexWithoutGroupsUsesWholeMatch() {
        Map<String, Value> values = reader.read(ok("balance=42"), List.of(regex("n", "\\d+", ValueType.NUMBER))).values();
        assertNumber("42", values.get("n"));
    }

    @Test
    void regexErrors() {
        assertError(reader.read(ok("x"), List.of(regex("r", "(", ValueType.NUMBER))), "r: invalid regex");
        assertError(reader.read(ok("x"), List.of(regex("r", "y(\\d)", ValueType.NUMBER))), "r: regex did not match");
        assertError(reader.read(ok("x"), List.of(regex("r", "x|(y)", ValueType.NUMBER))), "did not participate");
        assertError(reader.read(ok("x"), List.of(regexCollection("r", "(x)", WatchNumberFormat.automatic()))),
                "named groups");
        assertError(reader.read(ok("a=1 a=2"), List.of(regexCollection("r", "(?<key>\\w)=(?<value>\\d)",
                WatchNumberFormat.automatic()))), "r[a]: duplicate key");
        assertError(reader.read(ok("x"), List.of(regex("", "x", ValueType.NUMBER))), "Observation #1 has no name");
    }

    @Test
    void nullInputsAreTolerated() {
        assertTrue(reader.read(null, null).values().isEmpty());
        assertTrue(new WatchObservationReader(null).read(ok("{}"), List.of()).errors().isEmpty());
    }

    private static void assertError(WatchObservationReader.Reading reading, String fragment) {
        assertTrue(reading.errors().stream().anyMatch(e -> e.contains(fragment)),
                () -> "errors " + reading.errors() + " lack '" + fragment + "'");
    }
}
