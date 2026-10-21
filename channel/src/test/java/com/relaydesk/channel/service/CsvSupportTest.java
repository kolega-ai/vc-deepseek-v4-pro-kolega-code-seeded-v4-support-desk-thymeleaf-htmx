package com.relaydesk.channel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvSupportTest {

    @Test
    void parsesQuotedFieldsAndEscapedQuotes() {
        String input = "\"a,b\",\"c\"\"d\",plain\r\n\"multi\nline\",x,y";
        List<String[]> rows = CsvSupport.parse(input);
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"a,b", "c\"d", "plain"}, rows.get(0));
        assertArrayEquals(new String[]{"multi\nline", "x", "y"}, rows.get(1));
    }

    @Test
    void roundTripThroughEncoder() {
        List<String> fields = List.of("hello", "comma,value", "quote\"value", "line\nbreak", "");
        String encoded = CsvSupport.encode(fields);
        List<String[]> parsed = CsvSupport.parse(encoded);
        assertEquals(1, parsed.size());
        assertArrayEquals(fields.toArray(new String[0]), parsed.get(0));
    }

    @Test
    void encoderQuotesExactlyWhenNeeded() {
        assertEquals("\"a,b\",\"he said \"\"hi\"\"\",\"line1\nline2\",plain",
                CsvSupport.encode(List.of("a,b", "he said \"hi\"", "line1\nline2", "plain")));
    }

    @Test
    void rejectsUnterminatedQuotes() {
        assertThrows(IllegalArgumentException.class, () -> CsvSupport.parse("\"abc"));
    }

    @Test
    void rejectsOversizedRow() {
        assertThrows(IllegalArgumentException.class, () -> CsvSupport.parse("x".repeat(5001)));
    }

    @Test
    void crlfAndLfBothEndRows() {
        assertEquals(3, CsvSupport.parse("a,b\nc,d\r\ne,f").size());
        assertEquals(1, CsvSupport.parse("a,b").size());
    }
}
