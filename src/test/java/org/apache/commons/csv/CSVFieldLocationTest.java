/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *         https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests field position tracking, including quoted and escaped content, non-ASCII characters, CRLF, fields spanning
 * multiple lines, empty fields, missing trailing line breaks, comments and invalid quotes.
 */
class CSVFieldLocationTest {

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setEscape('\\')
            .setTrackFieldPositions(true)
            .get();

    /**
     * Record 0: a simple field, a field with a doubled quote and a field with an escape sequence, joined by CRLF.
     * Record 1: a quoted field spread over two physical lines containing Chinese text.
     * Record 2: an empty field, a Chinese field and a quoted empty field, with no trailing line break.
     */
    private static final String INPUT =
            "abc,\"he said \"\"hi\"\"\",\\t,end\r\n"
            + "\"line one 中文\r\n第二行\",x\r\n"
            + ",中文,\"\"";

    private static List<CSVRecord> parse(final Reader reader) throws IOException {
        try (CSVParser parser = new CSVParser(reader, FORMAT)) {
            return parser.getRecords();
        }
    }

    private void assertValueSpansRebuildValue(final String input, final CSVRecord record) {
        for (int fieldIndex = 0; fieldIndex < record.size(); fieldIndex++) {
            final CSVFieldLocation location = record.getFieldLocation(fieldIndex);
            final int field = fieldIndex;
            final String value = record.get(fieldIndex);
            final StringBuilder fromRaw = new StringBuilder();
            int expectedValueStart = 0;
            for (final CSVValueSpan span : location.getValueSpans()) {
                assertEquals(expectedValueStart, span.getValueStart());
                assertEquals(1, span.getValueLength());
                // Each span maps one decoded unit: take it from the decoded value, while asserting its raw extent.
                fromRaw.append(value, span.getValueStart(), span.getValueStart() + span.getValueLength());
                if (span.getRawEnd() - span.getRawStart() == 1) {
                    assertEquals(value.charAt(span.getValueStart()), input.charAt((int) span.getRawStart()));
                }
                expectedValueStart += span.getValueLength();
            }
            assertEquals(value, fromRaw.toString(),
                    () -> "rebuilt value for field " + field + " of record " + record.getRecordNumber());
            assertEquals(value.length(), expectedValueStart);
        }
    }

    @Test
    void testChineseAndCrlfAndMultilineField() throws IOException {
        final List<CSVRecord> records = parse(new StringReader(INPUT));

        assertEquals(3, records.size());

        final CSVRecord record0 = records.get(0);
        assertEquals(List.of("abc", "he said \"hi\"", "\t", "end"), record0.toList());
        final CSVFieldLocation abc = record0.getFieldLocation(0);
        assertEquals(0, abc.getRawStart());
        assertEquals(3, abc.getRawEnd());
        assertEquals("abc", INPUT.substring((int) abc.getRawStart(), (int) abc.getRawEnd()));

        final CSVFieldLocation quoted = record0.getFieldLocation(1);
        // The raw span includes the original quotes and the doubled quotes.
        assertEquals("\"he said \"\"hi\"\"\"", INPUT.substring((int) quoted.getRawStart(), (int) quoted.getRawEnd()));
        // The doubled quote maps a two-unit raw span ("") onto the single decoded quote.
        final List<CSVValueSpan> quoteSpans = quoted.getValueSpans();
        assertEquals(record0.get(1).length(), quoteSpans.stream().mapToInt(CSVValueSpan::getValueLength).sum());
        int quoteIndex = record0.get(1).indexOf('"');
        final CSVValueSpan quoteSpan = quoteSpans.get(quoteIndex);
        assertEquals(2, quoteSpan.getRawEnd() - quoteSpan.getRawStart());
        assertEquals(1, quoteSpan.getValueLength());
        assertEquals("\"\"", INPUT.substring((int) quoteSpan.getRawStart(), (int) quoteSpan.getRawEnd()));

        final CSVFieldLocation escaped = record0.getFieldLocation(2);
        assertEquals("\\t", INPUT.substring((int) escaped.getRawStart(), (int) escaped.getRawEnd()));
        assertEquals(1, escaped.getValueSpans().size());
        final CSVValueSpan escapeSpan = escaped.getValueSpans().get(0);
        assertEquals(2, escapeSpan.getRawEnd() - escapeSpan.getRawStart());
        assertEquals("\\t", INPUT.substring((int) escapeSpan.getRawStart(), (int) escapeSpan.getRawEnd()));
        assertEquals("\t", record0.get(2));

        final CSVFieldLocation end = record0.getFieldLocation(3);
        assertEquals("end", INPUT.substring((int) end.getRawStart(), (int) end.getRawEnd()));

        final CSVRecord record1 = records.get(1);
        assertEquals(2, record1.size());
        assertEquals("line one 中文\r\n第二行", record1.get(0));
        final CSVFieldLocation multiline = record1.getFieldLocation(0);
        // The raw span keeps the quotes and the CRLF inside the field.
        assertEquals("\"line one 中文\r\n第二行\"", INPUT.substring((int) multiline.getRawStart(), (int) multiline.getRawEnd()));

        final CSVRecord record2 = records.get(2);
        assertEquals(List.of("", "中文", ""), record2.toList());
        final CSVFieldLocation empty = record2.getFieldLocation(0);
        assertEquals(empty.getRawEnd(), empty.getRawStart());
        assertEquals(0, empty.getValueSpans().size());
        final CSVFieldLocation chinese = record2.getFieldLocation(1);
        assertEquals("中文", INPUT.substring((int) chinese.getRawStart(), (int) chinese.getRawEnd()));
        // UTF-16 code units: Chinese characters count one each, the same as String.substring().
        assertEquals(2, chinese.getRawEnd() - chinese.getRawStart());
        final CSVFieldLocation quotedEmpty = record2.getFieldLocation(2);
        assertEquals("\"\"", INPUT.substring((int) quotedEmpty.getRawStart(), (int) quotedEmpty.getRawEnd()));
        assertEquals(0, quotedEmpty.getValueSpans().size());

        records.forEach(record -> assertValueSpansRebuildValue(INPUT, record));
    }

    @Test
    void testChunkedReaderReportsIdenticalLocations() throws IOException {
        final List<CSVRecord> bufferedRecords = parse(new StringReader(INPUT));
        final List<CSVRecord> chunkedRecords = parse(new ChunkedReader(INPUT));

        assertEquals(bufferedRecords.size(), chunkedRecords.size());
        for (int recordIndex = 0; recordIndex < bufferedRecords.size(); recordIndex++) {
            final CSVRecord expected = bufferedRecords.get(recordIndex);
            final CSVRecord actual = chunkedRecords.get(recordIndex);
            assertEquals(expected.toList(), actual.toList());
            assertEquals(expected.getCharacterPosition(), actual.getCharacterPosition());
            assertEquals(expected.size(), actual.size());
            for (int fieldIndex = 0; fieldIndex < expected.size(); fieldIndex++) {
                final int field = fieldIndex;
                final int record = recordIndex;
                assertEquals(expected.getFieldLocation(field), actual.getFieldLocation(field),
                        () -> "field " + field + " of record " + record);
                final CSVFieldLocation location = actual.getFieldLocation(field);
                final String raw = INPUT.substring((int) location.getRawStart(), (int) location.getRawEnd());
                final CSVFieldLocation bufferedLocation = bufferedRecords.get(recordIndex).getFieldLocation(field);
                assertEquals(raw,
                        INPUT.substring((int) bufferedLocation.getRawStart(), (int) bufferedLocation.getRawEnd()));
            }
        }
    }

    @Test
    void testConsecutiveEmptyFieldsAndRecords() throws IOException {
        final String input = ",,a\r\n,,\r\n";
        final List<CSVRecord> records = parse(new StringReader(input));

        assertEquals(2, records.size());
        final CSVRecord first = records.get(0);
        assertEquals(List.of("", "", "a"), first.toList());
        assertEquals(0, first.getFieldLocation(0).getRawStart());
        assertEquals(0, first.getFieldLocation(0).getRawEnd());
        assertEquals(1, first.getFieldLocation(1).getRawStart());
        assertEquals(1, first.getFieldLocation(1).getRawEnd());
        assertEquals("a", input.substring((int) first.getFieldLocation(2).getRawStart(),
                (int) first.getFieldLocation(2).getRawEnd()));

        final CSVRecord second = records.get(1);
        assertEquals(List.of("", "", ""), second.toList());
        // Zero-width spans point at the delimiter or the record terminator.
        assertEquals(5, second.getFieldLocation(0).getRawStart());
        assertEquals(5, second.getFieldLocation(0).getRawEnd());
        assertEquals(6, second.getFieldLocation(1).getRawStart());
        assertEquals(6, second.getFieldLocation(1).getRawEnd());
        assertEquals(7, second.getFieldLocation(2).getRawStart());
        assertEquals(7, second.getFieldLocation(2).getRawEnd());
    }

    @Test
    void testCommentIsSkippedAndOffsetsStayCorrect() throws IOException {
        final CSVFormat format = FORMAT.builder().setCommentMarker('#').get();
        final String input = "# comment line\r\na,b\r\n";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(1, records.size());
            final CSVRecord record = records.get(0);
            assertEquals("a", input.substring((int) record.getFieldLocation(0).getRawStart(),
                    (int) record.getFieldLocation(0).getRawEnd()));
            assertEquals("b", input.substring((int) record.getFieldLocation(1).getRawStart(),
                    (int) record.getFieldLocation(1).getRawEnd()));
            assertEquals("comment line", record.getComment());
        }
    }

    @Test
    void testDisabledByDefault() throws IOException {
        try (CSVParser parser = CSVFormat.RFC4180.parse(new StringReader("a,b\r\n"))) {
            final CSVRecord record = parser.iterator().next();
            assertNull(record.getFieldLocation(0));
            assertNull(record.getFieldLocations());
        }
    }

    @Test
    void testInvalidQuoteStillPositionsFields() throws IOException {
        final String input = "\"good\"bad,x\r\n";
        try (CSVParser parser = FORMAT.parse(new StringReader(input))) {
            final UncheckedIOException ex = assertThrows(UncheckedIOException.class, parser::getRecords);
            assertEquals("Invalid character between encapsulated token and delimiter at line: 1, position: 7", ex.getCause().getMessage());
        }
    }

    @Test
    void testRawSlicesAreStableWithEscapedDelimiter() throws IOException {
        final String input = "a\\,b,\"x\\\"y\"";
        final List<CSVRecord> records = parse(new StringReader(input));
        final CSVRecord record = records.get(0);
        assertEquals(List.of("a,b", "x\"y"), record.toList());
        assertEquals("a\\,b", input.substring((int) record.getFieldLocation(0).getRawStart(),
                (int) record.getFieldLocation(0).getRawEnd()));
        assertEquals("\"x\\\"y\"", input.substring((int) record.getFieldLocation(1).getRawStart(),
                (int) record.getFieldLocation(1).getRawEnd()));
        assertValueSpansRebuildValue(input, record);
    }

    @Test
    void testAllLocationsCollected() throws IOException {
        final List<CSVRecord> records = parse(new StringReader(INPUT));
        final List<CSVFieldLocation> all = new ArrayList<>();
        for (final CSVRecord record : records) {
            assertEquals(record.size(), record.getFieldLocations().length);
            for (final CSVFieldLocation location : record.getFieldLocations()) {
                all.add(location);
            }
        }
        assertEquals(9, all.size());
    }

    @Test
    void testIgnoreSurroundingSpacesAdjustsValueMapping() throws IOException {
        final CSVFormat format = FORMAT.builder().setIgnoreSurroundingSpaces(true).get();
        final String input = "  a  , b\r\n";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final CSVRecord record = parser.iterator().next();
            final CSVFieldLocation first = record.getFieldLocation(0);
            assertEquals("a  ", input.substring((int) first.getRawStart(), (int) first.getRawEnd()));
            assertEquals(1, first.getValueSpans().size());
            assertEquals("a", record.get(0));
            final CSVFieldLocation second = record.getFieldLocation(1);
            assertEquals("b", input.substring((int) second.getRawStart(), (int) second.getRawEnd()));
            assertEquals("b", record.get(1));
        }
    }

    @Test
    void testTrimShrinksValueSpans() throws IOException {
        final CSVFormat format = FORMAT.builder().setTrim(true).get();
        final String input = "a\tb, c \r\n";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final CSVRecord record = parser.iterator().next();
            assertEquals("a\tb", record.get(0));
            assertEquals("c", record.get(1));
            final CSVFieldLocation location = record.getFieldLocation(1);
            assertEquals(" c ", input.substring((int) location.getRawStart(), (int) location.getRawEnd()));
            assertEquals(1, location.getValueSpans().size());
            assertEquals("c", input.substring((int) location.getValueSpans().get(0).getRawStart(),
                    (int) location.getValueSpans().get(0).getRawEnd()));
        }
    }

    @Test
    void testTrimWithEscapedContentKeepsMappings() throws IOException {
        final CSVFormat format = FORMAT.builder().setIgnoreSurroundingSpaces(true).get();
        final String input = "  a\\tb  ,next";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final CSVRecord record = parser.iterator().next();
            assertEquals("a\tb", record.get(0));
            final CSVFieldLocation location = record.getFieldLocation(0);
            final StringBuilder rebuilt = new StringBuilder();
            for (final CSVValueSpan span : location.getValueSpans()) {
                rebuilt.append(record.get(0), span.getValueStart(), span.getValueStart() + span.getValueLength());
            }
            assertEquals("a\tb", rebuilt.toString());
            // The two-unit raw escape \t maps onto the single decoded tab.
            assertEquals(1, location.getValueSpans().stream()
                    .filter(span -> span.getRawEnd() - span.getRawStart() == 2 && span.getValueLength() == 1)
                    .count());
            assertEquals("next", record.get(1));
        }
    }

    @Test
    void testMultiCharacterDelimiterAnchorsEmptyField() throws IOException {
        final CSVFormat format = CSVFormat.RFC4180.builder().setDelimiter("||").setTrackFieldPositions(true).get();
        final String input = "a||||b";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final CSVRecord record = parser.iterator().next();
            assertEquals(List.of("a", "", "b"), record.toList());
            assertEquals(3, record.getFieldLocation(1).getRawStart());
            assertEquals(3, record.getFieldLocation(1).getRawEnd());
            assertEquals("a", input.substring((int) record.getFieldLocation(0).getRawStart(),
                    (int) record.getFieldLocation(0).getRawEnd()));
            assertEquals("b", input.substring((int) record.getFieldLocation(2).getRawStart(),
                    (int) record.getFieldLocation(2).getRawEnd()));
        }
    }

    @Test
    void testMultiCharacterDelimiterWithChunkedReader() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter("[|]")
                .setEscape('!')
                .setTrackFieldPositions(true)
                .get();
        final String input = "a[|]b![!|!]c[|]d";
        final List<CSVRecord> bufferedRecords;
        try (CSVParser parser = format.parse(new StringReader(input))) {
            bufferedRecords = parser.getRecords();
        }
        final List<CSVRecord> chunkedRecords;
        try (CSVParser parser = format.parse(new ChunkedReader(input))) {
            chunkedRecords = parser.getRecords();
        }
        assertEquals(bufferedRecords.get(0).toList(), chunkedRecords.get(0).toList());
        for (int fieldIndex = 0; fieldIndex < bufferedRecords.get(0).size(); fieldIndex++) {
            assertEquals(bufferedRecords.get(0).getFieldLocation(fieldIndex), chunkedRecords.get(0).getFieldLocation(fieldIndex));
        }
        final CSVRecord record = bufferedRecords.get(0);
        assertEquals(List.of("a", "b[|]c", "d"), record.toList());
        final CSVFieldLocation escaped = record.getFieldLocation(1);
        // The escaped delimiter raw text is six units (![!|!]) but decodes to the three-unit delimiter [|].
        final CSVValueSpan span = escaped.getValueSpans().stream()
                .filter(s -> s.getRawEnd() - s.getRawStart() != s.getValueLength())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a non-identity value span"));
        assertEquals(6, span.getRawEnd() - span.getRawStart());
        assertEquals(3, span.getValueLength());
    }

    @Test
    void testCharacterOffsetIsAddedToLocations() throws IOException {
        final long offset = 100;
        try (CSVParser parser = CSVParser.builder().setReader(new StringReader("a,b")).setFormat(FORMAT)
                .setCharacterOffset(offset).get()) {
            final CSVRecord record = parser.iterator().next();
            assertEquals(offset, record.getFieldLocation(0).getRawStart());
            assertEquals(offset + 1, record.getFieldLocation(0).getRawEnd());
            assertEquals(offset + 2, record.getFieldLocation(1).getRawStart());
            assertEquals(offset, record.getFieldLocation(0).getValueSpans().get(0).getRawStart());
        }
    }

    @Test
    void testTrailingDelimiterDropsFieldLocation() throws IOException {
        final CSVFormat format = FORMAT.builder().setTrailingDelimiter(true).get();
        final String input = "a,b,";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final CSVRecord record = parser.iterator().next();
            assertEquals(List.of("a", "b"), record.toList());
            assertEquals(2, record.getFieldLocations().length);
        }
    }

    @Test
    void testLfLineEndingsAndIgnoredEmptyLines() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setTrackFieldPositions(true).get();
        final String input = "a,b\n\nx\n";
        try (CSVParser parser = format.parse(new StringReader(input))) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(2, records.size());
            final CSVRecord second = records.get(1);
            assertEquals("x", input.substring((int) second.getFieldLocation(0).getRawStart(),
                    (int) second.getFieldLocation(0).getRawEnd()));
            // The ignored blank line still consumes its LF, so offsets stay accurate.
            assertEquals(4, second.getCharacterPosition());
            assertEquals(5, second.getFieldLocation(0).getRawStart());
        }
    }
}
