package com.relaydesk.channel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Small hand-written RFC 4180 CSV parser/encoder used by the import/export features.
 * Handles quoted fields, escaped quotes ("") and CRLF/LF record endings; rejects
 * oversized rows and excessive row counts.
 */
final class CsvSupport {

    static final int MAX_ROW_CHARS = 5000;
    static final int MAX_ROWS = 2000;

    private CsvSupport() {
    }

    /** Parses CSV text into rows of fields. Blank input yields no rows. */
    static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int rowChars = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                rows.add(fields.toArray(new String[0]));
                fields = new ArrayList<>();
                rowChars = 0;
                if (rows.size() > MAX_ROWS) {
                    throw new IllegalArgumentException("too many rows (max " + MAX_ROWS + ")");
                }
            } else {
                field.append(c);
                rowChars++;
                if (rowChars > MAX_ROW_CHARS) {
                    throw new IllegalArgumentException("row exceeds " + MAX_ROW_CHARS + " characters");
                }
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            rows.add(fields.toArray(new String[0]));
            if (rows.size() > MAX_ROWS) {
                throw new IllegalArgumentException("too many rows (max " + MAX_ROWS + ")");
            }
        }
        return rows;
    }

    /** Encodes one CSV record (RFC 4180 quoting, CRLF-free). */
    static String encode(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(encodeField(fields.get(i)));
        }
        return sb.toString();
    }

    private static String encodeField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
