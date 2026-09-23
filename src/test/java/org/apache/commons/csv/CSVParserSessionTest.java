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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link CSVParserSession}.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class CSVParserSessionTest {

    /** Expected parse result, captured from a {@link CSVParser} over the same text. */
    private static final class Expected {

        final List<CSVRecord> records;

        final long recordNumber;

        final long lineNumber;

        final long characterPosition;

        final String firstEndOfLine;

        Expected(final CSVParser parser, final List<CSVRecord> records) {
            this.records = records;
            this.recordNumber = parser.getRecordNumber();
            this.lineNumber = parser.getCurrentLineNumber();
            this.characterPosition = parser.getCharacterPosition();
            this.firstEndOfLine = parser.getFirstEndOfLine();
        }
    }

    /** Quoted fields, nested escaped quotes, an in-field CRLF, empty fields and a trailing record without a line break. */
    private static final String RECORDS_INPUT = "\"a\",\"b\",\"c\"\r\n1,\"two \"\"quoted\"\"\",\"line1\r\nline2\"\r\n,,\"\"\r\nlast,\"x,y\",z";

    /** Comments, a blank line and an escaped quote, with a trailing record without a line break. */
    private static final String COMMENTS_INPUT = "# header comment\na,\"b\"\n\n# another comment\nc,\"d\"\"e\"";

    /** Mixed CR, LF and CRLF line breaks. */
    private static final String MIXED_EOL_INPUT = "a,b\rc,d\ne,f\r\n1,2";

    private static final CSVFormat COMMENT_FORMAT = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();

    private static final CSVFormat RFC_COMMENT_FORMAT = CSVFormat.RFC4180.builder().setCommentMarker('#').get();

    static Stream<Arguments> csvInputs() {
        return Stream.of(
                Arguments.of(CSVFormat.DEFAULT, RECORDS_INPUT),
                Arguments.of(CSVFormat.RFC4180, RECORDS_INPUT),
                Arguments.of(COMMENT_FORMAT, COMMENTS_INPUT),
                Arguments.of(RFC_COMMENT_FORMAT, COMMENTS_INPUT),
                Arguments.of(CSVFormat.DEFAULT, MIXED_EOL_INPUT),
                Arguments.of(CSVFormat.RFC4180, MIXED_EOL_INPUT));
    }

    private static Expected parseExpected(final String input, final CSVFormat format) throws IOException {
        try (CSVParser parser = CSVParser.parse(input, format)) {
            return new Expected(parser, parser.getRecords());
        }
    }

    private static void assertSessionMatches(final Expected expected, final List<CSVRecord> actual, final CSVParserSession session,
            final String label) {
        assertEquals(expected.records.size(), actual.size(), label + ": record count");
        for (int i = 0; i < expected.records.size(); i++) {
            final CSVRecord expectedRecord = expected.records.get(i);
            final CSVRecord actualRecord = actual.get(i);
            final String recordLabel = label + ": record " + i;
            assertArrayEquals(expectedRecord.values(), actualRecord.values(), recordLabel + " values");
            assertEquals(expectedRecord.getRecordNumber(), actualRecord.getRecordNumber(), recordLabel + " record number");
            assertEquals(expectedRecord.getCharacterPosition(), actualRecord.getCharacterPosition(), recordLabel + " character position");
            assertEquals(expectedRecord.getComment(), actualRecord.getComment(), recordLabel + " comment");
        }
        assertEquals(expected.recordNumber, session.getRecordNumber(), label + ": session record number");
        assertEquals(expected.lineNumber, session.getCurrentLineNumber(), label + ": session line number");
        assertEquals(expected.characterPosition, session.getCharacterPosition(), label + ": session character position");
        assertEquals(expected.firstEndOfLine, session.getFirstEndOfLine(), label + ": first end of line");
    }

    @ParameterizedTest
    @MethodSource("csvInputs")
    void testCharByCharParity(final CSVFormat format, final String input) throws Exception {
        final Expected expected = parseExpected(input, format);
        try (CSVParserSession session = CSVParserSession.create(format)) {
            final List<CSVRecord> actual = new ArrayList<>();
            for (int i = 0; i < input.length(); i++) {
                actual.addAll(session.feed(input.substring(i, i + 1)));
            }
            actual.addAll(session.finish());
            assertTrue(session.isFinished());
            assertSessionMatches(expected, actual, session, "char-by-char");
        }
    }

    @ParameterizedTest
    @MethodSource("csvInputs")
    void testEverySplitPointParity(final CSVFormat format, final String input) throws Exception {
        final Expected expected = parseExpected(input, format);
        for (int split = 0; split <= input.length(); split++) {
            try (CSVParserSession session = CSVParserSession.create(format)) {
                final List<CSVRecord> actual = new ArrayList<>();
                actual.addAll(session.feed(input.substring(0, split)));
                actual.addAll(session.feed(input.substring(split)));
                actual.addAll(session.finish());
                assertSessionMatches(expected, actual, session, "split=" + split);
            }
        }
    }

    @Test
    void testClose() throws Exception {
        final CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT);
        assertTrue(session.feed("a,\"open").isEmpty());
        session.close();
        assertThrows(IllegalStateException.class, () -> session.feed("x"));
        assertThrows(IllegalStateException.class, session::finish);
        session.close();
    }

    @Test
    void testEmptyInput() throws Exception {
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT)) {
            assertTrue(session.finish().isEmpty());
            assertTrue(session.isFinished());
            assertFalse(session.isFailed());
            assertEquals(0, session.getRecordNumber());
            assertTrue(session.finish().isEmpty(), "repeated finish returns an empty list");
            assertThrows(IllegalStateException.class, () -> session.feed("a"));
        }
    }

    @Test
    void testFeedDoesNotCommitTailRecord() throws Exception {
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT)) {
            assertTrue(session.feed("a,b").isEmpty(), "no line break yet: nothing may be delivered");
            assertTrue(session.feed(",c").isEmpty());
            assertFalse(session.isFinished());
            final List<CSVRecord> records = session.finish();
            assertEquals(1, records.size());
            assertArrayEquals(new String[] { "a", "b", "c" }, records.get(0).values());
        }
    }

    @Test
    void testFeedSplitCrLfAndQuotedLineBreak() throws Exception {
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.RFC4180)) {
            assertTrue(session.feed("1,\"x\r").isEmpty());
            assertTrue(session.feed("\ny\"").isEmpty());
            assertTrue(session.feed("\r").isEmpty(), "a trailing CR must wait for a possible LF");
            final List<CSVRecord> records = session.feed("\n");
            assertEquals(1, records.size());
            assertArrayEquals(new String[] { "1", "x\r\ny" }, records.get(0).values());
            assertTrue(session.finish().isEmpty());
        }
    }

    @Test
    void testHeaders() throws Exception {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().get();
        final String input = "h1,h2\n1,2\n3,4\n";
        final Expected expected = parseExpected(input, format);
        try (CSVParserSession session = CSVParserSession.create(format)) {
            final List<CSVRecord> actual = new ArrayList<>(session.feed(input));
            actual.addAll(session.finish());
            assertSessionMatches(expected, actual, session, "headers");
            assertEquals(Arrays.asList("h1", "h2"), session.getHeaderNames());
            assertEquals(2, actual.size());
            assertEquals("2", actual.get(0).get("h2"));
            assertEquals("4", actual.get(1).get("h2"));
        }
    }

    @Test
    void testHeaderComment() throws Exception {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setCommentMarker('#').get();
        final String input = "# note\nh1,h2\n1,2\n";
        final String expectedComment;
        try (CSVParser parser = CSVParser.parse(input, format)) {
            parser.getRecords();
            expectedComment = parser.getHeaderComment();
        }
        try (CSVParserSession session = CSVParserSession.create(format)) {
            session.feed(input);
            session.finish();
            assertEquals("note", expectedComment);
            assertEquals(expectedComment, session.getHeaderComment());
        }
    }

    @Test
    void testInvalidArguments() throws Exception {
        assertThrows(NullPointerException.class, () -> CSVParserSession.create(null));
        assertThrows(NullPointerException.class, () -> CSVParserSession.builder().get());
        assertThrows(IllegalArgumentException.class, () -> CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(0));
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT)) {
            assertThrows(NullPointerException.class, () -> session.feed(null));
        }
    }

    @Test
    void testMaxRecordCharsCountsUtf16CodeUnits() throws Exception {
        // A surrogate pair counts as two UTF-16 code units: the record is 5 units long.
        final String input = "\"\uD83D\uDE42\"\n";
        try (CSVParserSession session = CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(5).get()) {
            assertEquals(1, session.feed(input).size());
            assertTrue(session.finish().isEmpty());
        }
        try (CSVParserSession session = CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(4).get()) {
            assertThrows(CSVException.class, () -> session.feed(input));
            assertTrue(session.isFailed());
        }
    }

    @Test
    void testMaxRecordCharsExactFit() throws Exception {
        try (CSVParserSession session = CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(6).get()) {
            assertEquals(1, session.feed("ab,cd\n").size(), "exactly 6 code units including the line break");
            assertTrue(session.finish().isEmpty());
        }
        try (CSVParserSession session = CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(6).get()) {
            assertTrue(session.feed("ab,cde").isEmpty());
            assertEquals(1, session.finish().size(), "exactly 6 code units without a line break");
        }
    }

    @Test
    void testMaxRecordCharsExceeded() throws Exception {
        final CSVParserSession session = CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(6).get();
        try {
            final List<CSVRecord> delivered = session.feed("ok\n");
            assertEquals(1, delivered.size());
            assertThrows(CSVException.class, () -> session.feed("too-long\n"));
            assertTrue(session.isFailed());
            assertThrows(IllegalStateException.class, () -> session.feed("x\n"), "feed is rejected after a failure");
            assertThrows(CSVException.class, session::finish);
            assertArrayEquals(new String[] { "ok" }, delivered.get(0).values(), "records delivered before the failure stay valid");
        } finally {
            session.close();
        }
    }

    @Test
    void testMaxRecordCharsExceededAcrossChunks() throws Exception {
        try (CSVParserSession session = CSVParserSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(6).get()) {
            assertTrue(session.feed("ab,").isEmpty());
            assertThrows(CSVException.class, () -> session.feed("cde\n"));
            assertTrue(session.isFailed());
        }
    }

    @Test
    void testMaxRecordCharsIgnoresHeader() throws Exception {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().get();
        try (CSVParserSession session = CSVParserSession.builder().setFormat(format).setMaxRecordChars(10).get()) {
            final List<CSVRecord> records = session.feed("long-header-name,other\na,b\n");
            assertEquals(1, records.size());
            assertArrayEquals(new String[] { "a", "b" }, records.get(0).values());
            assertTrue(session.finish().isEmpty());
        }
    }

    @Test
    void testRecordNumberingAndPositions() throws Exception {
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT)) {
            final List<CSVRecord> records = session.feed("a,b\n1,2\n");
            assertEquals(2, records.size());
            assertEquals(1, records.get(0).getRecordNumber(), "record numbers are 1-based");
            assertEquals(2, records.get(1).getRecordNumber());
            assertEquals(0, records.get(0).getCharacterPosition(), "character positions are 0-based");
            assertEquals(4, records.get(1).getCharacterPosition());
            assertEquals(2, session.getRecordNumber());
            assertTrue(session.finish().isEmpty());
        }
    }

    @Test
    void testRepeatedFinish() throws Exception {
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT)) {
            assertEquals(2, session.feed("a\nb\n").size());
            assertTrue(session.finish().isEmpty());
            assertTrue(session.isFinished());
            assertTrue(session.finish().isEmpty(), "repeated finish returns an empty list");
        }
    }

    @Test
    void testSkipHeaderRecord() throws Exception {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader("a", "b").setSkipHeaderRecord(true).get();
        try (CSVParserSession session = CSVParserSession.create(format)) {
            final List<CSVRecord> records = session.feed("x,y\n1,2\n");
            assertTrue(session.finish().isEmpty());
            assertEquals(Arrays.asList("a", "b"), session.getHeaderNames());
            assertEquals(1, records.size());
            assertEquals("2", records.get(0).get("b"));
        }
    }

    @Test
    void testTrailerComment() throws Exception {
        final String input = "a,b\n# trailer\n";
        final String expectedComment;
        try (CSVParser parser = CSVParser.parse(input, COMMENT_FORMAT)) {
            parser.getRecords();
            expectedComment = parser.getTrailerComment();
        }
        try (CSVParserSession session = CSVParserSession.create(COMMENT_FORMAT)) {
            assertNull(session.getTrailerComment(), "no trailer comment before finish");
            assertEquals(1, session.feed(input).size());
            assertTrue(session.finish().isEmpty());
            assertEquals("trailer", expectedComment);
            assertEquals(expectedComment, session.getTrailerComment());
        }
    }

    @Test
    void testUnclosedQuoteAtFinish() throws Exception {
        final CSVParserSession session = CSVParserSession.create(CSVFormat.DEFAULT);
        try {
            assertTrue(session.feed("a,\"unclosed").isEmpty(), "an open quoted field is not a complete record");
            assertThrows(CSVException.class, session::finish);
            assertTrue(session.isFailed());
            assertThrows(IllegalStateException.class, () -> session.feed("x"));
            assertThrows(CSVException.class, session::finish, "a failed session keeps reporting the failure");
        } finally {
            session.close();
        }
    }
}
