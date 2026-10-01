/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.commons.csv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVFieldRange} and the position tracking mode enabled through
 * {@link CSVFormat.Builder#setTrackPositions(boolean)}.
 */
class CSVFieldRangeTest {

    /**
     * A reader that delivers a single character per read call, to prove that tracked positions do not depend on how
     * the source chunks its data.
     */
    private static final class OneCharReader extends Reader {

        private final String source;
        private int pos;

        OneCharReader(final String source) {
            this.source = source;
        }

        @Override
        public void close() {
            // noop
        }

        @Override
        public int read() {
            return pos < source.length() ? source.charAt(pos++) : -1;
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) {
            if (len == 0) {
                return 0;
            }
            if (pos >= source.length()) {
                return -1;
            }
            cbuf[off] = source.charAt(pos++);
            return 1;
        }
    }

    private static final CSVFormat TRACKING = CSVFormat.DEFAULT.builder().setTrackPositions(true).get();

    private static void assertSlice(final String input, final String expected, final CSVFieldRange range) {
        assertEquals(expected, input.substring((int) range.getStartOffset(), (int) range.getEndOffset()));
    }

    private static List<CSVRecord> parse(final Reader reader, final CSVFormat format) throws IOException {
        try (CSVParser parser = new CSVParser(reader, format)) {
            return parser.getRecords();
        }
    }

    @Test
    void testQuotesCjkCrlfAndMultilineField() throws IOException {
        // Double-quote escapes, Chinese characters, CRLF line ends and a field spanning lines.
        final String input = "name,note\r\n\"张\"\"三\",\"line1\nline2\"\r\nplain,end\r\n";
        final List<CSVRecord> records = parse(new StringReader(input), TRACKING);
        assertEquals(3, records.size());

        // Header record.
        final CSVRecord header = records.get(0);
        assertTrue(header.hasFieldRanges());
        assertSlice(input, "name", header.getFieldRange(0));
        assertSlice(input, "note", header.getFieldRange(1));
        assertEquals(0, header.getFieldRange(0).getStartOffset());
        assertEquals(4, header.getFieldRange(0).getEndOffset());

        // Quoted field with a doubled quote and Chinese characters.
        final CSVRecord second = records.get(1);
        assertEquals("张\"三", second.get(0));
        final CSVFieldRange quoted = second.getFieldRange(0);
        assertSlice(input, "\"张\"\"三\"", quoted);
        assertEquals(input.indexOf('\"'), quoted.getStartOffset());
        // Decoded value mapping: each CJK character maps to itself, the quote maps to the doubled "".
        assertEquals(3, quoted.getValueLength());
        assertEquals("张", input.substring((int) quoted.getValueStartOffset(0), (int) quoted.getValueEndOffset(0)));
        assertEquals("\"\"", input.substring((int) quoted.getValueStartOffset(1), (int) quoted.getValueEndOffset(1)));
        assertEquals("三", input.substring((int) quoted.getValueStartOffset(2), (int) quoted.getValueEndOffset(2)));

        // Multiline field: the raw range spans the embedded line feed.
        final CSVFieldRange multiline = second.getFieldRange(1);
        assertSlice(input, "\"line1\nline2\"", multiline);
        assertEquals("line1\nline2", second.get(1));
        assertEquals(11, multiline.getValueLength());
        for (int i = 0; i < 11; i++) {
            assertEquals(String.valueOf("line1\nline2".charAt(i)),
                    input.substring((int) multiline.getValueStartOffset(i), (int) multiline.getValueEndOffset(i)));
        }

        // Plain fields.
        final CSVRecord third = records.get(2);
        assertSlice(input, "plain", third.getFieldRange(0));
        assertSlice(input, "end", third.getFieldRange(1));
        assertEquals(input.length() - 2, third.getFieldRange(1).getEndOffset());
    }

    @Test
    void testOneCharReaderProducesSameRanges() throws IOException {
        final String input = "name,note\r\n\"张\"\"三\",\"line1\nline2\"\r\nplain,end\r\n";
        final List<CSVRecord> expected = parse(new StringReader(input), TRACKING);
        final List<CSVRecord> actual = parse(new OneCharReader(input), TRACKING);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).values(), actual.get(i).values());
            assertArrayEquals(expected.get(i).getFieldRanges(), actual.get(i).getFieldRanges());
        }
    }

    @Test
    void testEmptyFields() throws IOException {
        final String input = "a,,,\"\"\r\n";
        final List<CSVRecord> records = parse(new StringReader(input), TRACKING);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertEquals(4, record.size());
        assertSlice(input, "a", record.getFieldRange(0));
        // Empty unquoted fields get a zero-width range where the field would start.
        assertEquals(2, record.getFieldRange(1).getStartOffset());
        assertEquals(2, record.getFieldRange(1).getEndOffset());
        assertEquals(0, record.getFieldRange(1).getValueLength());
        assertEquals(3, record.getFieldRange(2).getStartOffset());
        assertEquals(3, record.getFieldRange(2).getEndOffset());
        // A quoted empty field covers both quote characters.
        assertSlice(input, "\"\"", record.getFieldRange(3));
        assertEquals(0, record.getFieldRange(3).getValueLength());
    }

    @Test
    void testNoTrailingNewline() throws IOException {
        final String input = "a,b";
        final List<CSVRecord> records = parse(new StringReader(input), TRACKING);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertSlice(input, "a", record.getFieldRange(0));
        assertSlice(input, "b", record.getFieldRange(1));
        assertEquals(input.length(), record.getFieldRange(1).getEndOffset());
    }

    @Test
    void testEmptyLastFieldWithoutNewline() throws IOException {
        final String input = "a,";
        final List<CSVRecord> records = parse(new StringReader(input), TRACKING);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertEquals(2, record.size());
        assertEquals("", record.get(1));
        assertEquals(input.length(), record.getFieldRange(1).getStartOffset());
        assertEquals(input.length(), record.getFieldRange(1).getEndOffset());
    }

    @Test
    void testComments() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setTrackPositions(true).setCommentMarker('#').get();
        final String input = "# a comment\r\na,b\r\n# another\r\nc,d\r\n";
        final List<CSVRecord> records = parse(new StringReader(input), format);
        assertEquals(2, records.size());
        assertEquals("a comment", records.get(0).getComment());
        assertSlice(input, "a", records.get(0).getFieldRange(0));
        assertSlice(input, "b", records.get(0).getFieldRange(1));
        assertEquals(input.indexOf("a,b"), records.get(0).getFieldRange(0).getStartOffset());
        assertSlice(input, "c", records.get(1).getFieldRange(0));
        assertSlice(input, "d", records.get(1).getFieldRange(1));
        assertEquals(input.indexOf("c,d"), records.get(1).getFieldRange(0).getStartOffset());
    }

    @Test
    void testInvalidQuoteThrows() {
        final String input = "\"abc\"def,x\r\n";
        final UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> parse(new StringReader(input), TRACKING));
        assertTrue(e.getCause() instanceof CSVException);
    }

    @Test
    void testTrailingDataAfterClosingQuote() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setTrackPositions(true).setTrailingData(true).get();
        final String input = "\"abc\"def,x\r\n";
        final List<CSVRecord> records = parse(new StringReader(input), format);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertEquals("abcdef", record.get(0));
        assertSlice(input, "\"abc\"def", record.getFieldRange(0));
        assertSlice(input, "x", record.getFieldRange(1));
    }

    @Test
    void testEscapedDelimiterMapping() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setTrackPositions(true).setEscape('\\').get();
        final String input = "a\\,b,c\r\n";
        final List<CSVRecord> records = parse(new StringReader(input), format);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertEquals("a,b", record.get(0));
        final CSVFieldRange range = record.getFieldRange(0);
        assertSlice(input, "a\\,b", range);
        // The decoded comma maps to the two-character escape sequence.
        assertEquals("\\,", input.substring((int) range.getValueStartOffset(1), (int) range.getValueEndOffset(1)));
        assertSlice(input, "c", record.getFieldRange(1));
    }

    @Test
    void testTrimAlignsValueMapping() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setTrackPositions(true).setTrim(true).get();
        final String input = "  ab  ,cd\r\n";
        final List<CSVRecord> records = parse(new StringReader(input), format);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertEquals("ab", record.get(0));
        final CSVFieldRange range = record.getFieldRange(0);
        // The raw range covers the untrimmed field text.
        assertSlice(input, "  ab  ", range);
        // The value mapping follows the trimmed value.
        assertEquals(2, range.getValueLength());
        assertEquals("a", input.substring((int) range.getValueStartOffset(0), (int) range.getValueEndOffset(0)));
        assertEquals("b", input.substring((int) range.getValueStartOffset(1), (int) range.getValueEndOffset(1)));
    }

    @Test
    void testUntrackedByDefault() throws IOException {
        final List<CSVRecord> records = parse(new StringReader("a,b\r\n"), CSVFormat.DEFAULT);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertFalse(record.hasFieldRanges());
        assertNull(record.getFieldRanges());
        assertThrows(IllegalStateException.class, () -> record.getFieldRange(0));
    }

    @Test
    void testCharacterOffset() throws IOException {
        final String input = "a,b\r\n";
        try (CSVParser parser = CSVParser.builder().setReader(new StringReader(input)).setFormat(TRACKING).setCharacterOffset(100).get()) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(1, records.size());
            final CSVRecord record = records.get(0);
            assertEquals(100, record.getFieldRange(0).getStartOffset());
            assertEquals(101, record.getFieldRange(0).getEndOffset());
            assertEquals(102, record.getFieldRange(1).getStartOffset());
            assertEquals(100, record.getFieldRange(0).getValueStartOffset(0));
        }
    }
}
