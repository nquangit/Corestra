package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.watch.WatchNumberFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WatchNumberParserTest {
    private final WatchNumberParser parser = new WatchNumberParser();
    private final WatchNumberFormat auto = WatchNumberFormat.automatic();

    private void assertNumber(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void autoParsesCommonFormats() {
        assertNumber("1234567.89", parser.parse("1,234,567.89", auto));
        assertNumber("1234567.89", parser.parse("1.234.567,89 VND", auto));
        assertNumber("-500000", parser.parse("-500,000", auto));
        assertNumber("1234.50", parser.parse("1 234,50", auto));
        assertNumber("12", parser.parse("+12", auto));
        assertNumber("-1000", parser.parse("(1,000)", auto));
        assertNumber("-1000", parser.parse("(1,000) USD", auto));
        assertNumber("1.5", parser.parse("1.5", auto));
        assertNumber("1234", parser.parse("1,234", auto));
        assertNumber("1234", parser.parse("1.234", auto));
        assertNumber("1234567", parser.parse("1.234.567", auto));
        assertNumber("12.34", parser.parse("12,34", auto));
        assertNumber("1.2345", parser.parse("1.2345", auto));
        assertNumber("$99.95".substring(1), parser.parse("$99.95", auto));
        assertNumber("-42", parser.parse("−42", auto));
        assertNumber("-42", parser.parse("42-", auto));
        assertNumber("1000", parser.parse("1 000", auto));
    }

    @Test
    void autoTreatsLeadingZeroIntegerAsDecimal() {
        assertNumber("0.500", parser.parse("0.500", auto));
        assertNumber("0.125", parser.parse(",125", auto));
    }

    @Test
    void scientificLiteralsParseExactly() {
        assertNumber("10000000", parser.parse("1.0E7", auto));
        assertNumber("-0.00012", parser.parse("-1.2e-4", auto));
    }

    @Test
    void manualFormatUsesConfiguredSeparators() {
        WatchNumberFormat eu = new WatchNumberFormat(false, ",", ".", 0, "[^0-9.,\\-]");
        assertNumber("1234567.89", parser.parse("1.234.567,89 VND", eu));
        assertNumber("1234", parser.parse("1.234", eu));
        WatchNumberFormat us = new WatchNumberFormat(false, ".", ",", 0, "");
        assertNumber("1234.5", parser.parse("1,234.5", us));
        WatchNumberFormat space = new WatchNumberFormat(false, ",", " ", 0, "");
        assertNumber("1234.5", parser.parse("1 234,5", space));
        assertNull(parser.parse("1234.5", space), "a dot is not valid when decimal is ','");
    }

    @Test
    void minorUnitsAreScaled() {
        WatchNumberFormat cents = new WatchNumberFormat(false, ".", "", 2, "");
        assertNumber("1234.56", parser.parse("123456", cents));
        WatchNumberFormat autoCents = new WatchNumberFormat(true, ".", ",", 2, "");
        assertNumber("-1.05", parser.parse("-105", autoCents));
    }

    @Test
    void unparseableReturnsNull() {
        assertNull(parser.parse(null, auto));
        assertNull(parser.parse("", auto));
        assertNull(parser.parse("   ", auto));
        assertNull(parser.parse("abc", auto));
        assertNull(parser.parse("-", auto));
        assertNull(parser.parse("1.2,3,4", auto), "decimal separator must occur once");
        assertNull(parser.parse("1-2", auto));
        assertNull(parser.parse("abc", new WatchNumberFormat(false, ".", ",", 0, "")));
        assertNull(parser.parse("12", new WatchNumberFormat(false, ".", ",", 0, "([")), "bad strip pattern");
    }

    @Test
    void nullFormatMeansAutomatic() {
        assertNumber("1234", parser.parse("1,234", null));
    }

    @Test
    void machineFirstKeepsPlainDecimalLiterals() {
        assertNumber("1.234", parser.parseMachineFirst("1.234", auto));
        assertNumber("10.125", parser.parseMachineFirst("10.125", auto));
        assertNumber("1234", parser.parseMachineFirst("1,234", auto));
        assertNumber("1234567", parser.parseMachineFirst("1.234.567", auto));
        assertNumber("-500000", parser.parseMachineFirst("-500000", auto));
        WatchNumberFormat eu = new WatchNumberFormat(false, ",", ".", 0, "");
        assertNumber("1234", parser.parseMachineFirst("1.234", eu));
    }
}
