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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVPushSession}.
 */
class CSVPushSessionTest {

    /** A fixture with a comment, an empty line, empty fields, doubled quotes and an in-field CRLF, ending without a line terminator. */
    private static final String FIXTURE = "# a comment\n" +
            "alpha,beta,gamma\n" +
            "\n" +
            "\"say \"\"hi\"\"\",30,\"multi\r\nline\"\r\n" +
            ",25,\n" +
            "bob,,\"a,b\"\n" +
            "# trailer comment\n" +
            "last,1,\"x\"";

    private static final String ESCAPE_FIXTURE = "a\\,b,\"q\\\"w\"\n" +
            "1,,3\n" +
            "\"x\",y,z";

    private static final CSVFormat DEFAULT_COMMENTS = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();

    private static final CSVFormat RFC4180_COMMENTS = CSVFormat.RFC4180.builder().setCommentMarker('#').get();

    private static final CSVFormat ESCAPE = CSVFormat.DEFAULT.builder().setEscape('\\').get();

    private static void assertRecordsEqual(final List<CSVRecord> expected, final List<CSVRecord> actual, final String message) {
        assertEquals(expected.size(), actual.size(), message + ": record count");
        for (int i = 0; i < expected.size(); i++) {
            final CSVRecord e = expected.get(i);
            final CSVRecord a = actual.get(i);
            final String at = message + ": record " + i;
            assertEquals(e.getRecordNumber(), a.getRecordNumber(), at + " record number");
            assertEquals(e.getCharacterPosition(), a.getCharacterPosition(), at + " character position");
            assertEquals(e.toList(), a.toList(), at + " values");
            assertEquals(e.getComment(), a.getComment(), at + " comment");
        }
    }

    private static List<CSVRecord> parseAll(final CSVFormat format, final String csv) throws IOException {
        try (CSVParser parser = CSVParser.parse(csv, format)) {
            return parser.getRecords();
        }
    }

    private void assertSplitPoints(final CSVFormat format, final String csv) throws IOException {
        final List<CSVRecord> expected = parseAll(format, csv);
        for (int i = 0; i <= csv.length(); i++) {
            final CSVPushSession session = new CSVPushSession(format);
            final List<CSVRecord> actual = new ArrayList<>();
            actual.addAll(session.feed(csv.substring(0, i)));
            actual.addAll(session.feed(csv.substring(i)));
            actual.addAll(session.finish());
            assertRecordsEqual(expected, actual, "split at " + i);
        }
    }

    private void assertCharByChar(final CSVFormat format, final String csv) throws IOException {
        final List<CSVRecord> expected = parseAll(format, csv);
        final CSVPushSession session = new CSVPushSession(format);
        final List<CSVRecord> actual = new ArrayList<>();
        for (int i = 0; i < csv.length(); i++) {
            actual.addAll(session.feed(csv.substring(i, i + 1)));
        }
        actual.addAll(session.finish());
        assertRecordsEqual(expected, actual, "char by char");
    }

    @Test
    void testSplitPointsDefault() throws IOException {
        assertSplitPoints(DEFAULT_COMMENTS, FIXTURE);
    }

    @Test
    void testSplitPointsRfc4180() throws IOException {
        assertSplitPoints(RFC4180_COMMENTS, FIXTURE);
    }

    @Test
    void testSplitPointsEscape() throws IOException {
        assertSplitPoints(ESCAPE, ESCAPE_FIXTURE);
    }

    @Test
    void testCharByCharDefault() throws IOException {
        assertCharByChar(DEFAULT_COMMENTS, FIXTURE);
    }

    @Test
    void testCharByCharRfc4180() throws IOException {
        assertCharByChar(RFC4180_COMMENTS, FIXTURE);
    }

    @Test
    void testCharByCharEscape() throws IOException {
        assertCharByChar(ESCAPE, ESCAPE_FIXTURE);
    }

    @Test
    void testFeedDoesNotCommitTrailingRecord() throws CSVException {
        final CSVPushSession session = new CSVPushSession(CSVFormat.DEFAULT);
        assertTrue(session.feed("a,b").isEmpty(), "feed must not commit a record without a line terminator");
        final List<CSVRecord> first = session.feed("\nc,d");
        assertEquals(1, first.size());
        assertEquals(Arrays.asList("a", "b"), first.get(0).toList());
        assertTrue(session.feed("").isEmpty(), "an empty chunk completes nothing");
        final List<CSVRecord> rest = session.finish();
        assertEquals(1, rest.size());
        assertEquals(Arrays.asList("c", "d"), rest.get(0).toList());
    }

    @Test
    void testCountsMatchReaderParser() throws IOException {
        final CSVPushSession session = new CSVPushSession(DEFAULT_COMMENTS);
        final List<CSVRecord> actual = new ArrayList<>();
        actual.addAll(session.feed(FIXTURE));
        actual.addAll(session.finish());
        assertEquals(1, actual.get(0).getRecordNumber(), "record numbers are 1-based");
        assertEquals(0, actual.get(0).getCharacterPosition(), "character positions are 0-based");
        assertEquals(actual.size(), session.getRecordNumber(), "delivered record count");
        try (CSVParser parser = CSVParser.parse(FIXTURE, DEFAULT_COMMENTS)) {
            parser.getRecords();
            assertEquals(parser.getRecordNumber(), session.getRecordNumber(), "record count");
            assertEquals(parser.getCurrentLineNumber(), session.getCurrentLineNumber(), "line number");
            assertEquals(parser.getFirstEndOfLine(), session.getFirstEndOfLine(), "first end of line");
        }
    }

    @Test
    void testRecordCharLimit() throws CSVException {
        final CSVPushSession session = CSVPushSession.builder().setFormat(CSVFormat.DEFAULT).setMaxRecordChars(10).get();
        final List<CSVRecord> first = session.feed("a,b\n");
        assertEquals(1, first.size());
        assertThrows(CSVException.class, () -> session.feed("123456,7890\n"), "12 characters exceed the limit of 10");
        assertTrue(session.isFailed());
        assertThrows(IllegalStateException.class, () -> session.feed("x\n"));
        assertThrows(IllegalStateException.class, session::finish);
        // Records delivered before the failure stay valid.
        assertEquals(Arrays.asList("a", "b"), first.get(0).toList());
        assertEquals(1, first.get(0).getRecordNumber());
    }

    @Test
    void testRecordCharLimitDisabledByDefault() throws CSVException {
        final CSVPushSession session = new CSVPushSession(CSVFormat.DEFAULT);
        final char[] big = new char[100_000];
        Arrays.fill(big, 'x');
        assertEquals(1, session.feed(new String(big) + "\n").size());
        assertTrue(session.finish().isEmpty());
    }

    @Test
    void testHeaderFromInput() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().get();
        final String csv = "a,b\n1,2\n3,4\n";
        final CSVPushSession session = new CSVPushSession(format);
        final List<CSVRecord> actual = new ArrayList<>();
        actual.addAll(session.feed("a,b\n1,"));
        assertEquals(Arrays.asList("a", "b"), session.getHeaderNames());
        actual.addAll(session.feed("2\n3,4\n"));
        actual.addAll(session.finish());
        assertRecordsEqual(parseAll(format, csv), actual, "header from input");
        assertEquals(2, actual.size());
        assertEquals("1", actual.get(0).get("a"));
        assertEquals("2", actual.get(0).get("b"));
        assertEquals(Integer.valueOf(1), session.getHeaderMap().get("b"));
    }

    @Test
    void testHeaderSkipped() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader("x", "y").setSkipHeaderRecord(true).get();
        final String csv = "a,b\n1,2\n";
        final CSVPushSession session = new CSVPushSession(format);
        final List<CSVRecord> actual = new ArrayList<>();
        actual.addAll(session.feed(csv));
        actual.addAll(session.finish());
        assertRecordsEqual(parseAll(format, csv), actual, "skipped header");
        assertEquals(1, actual.size());
        assertEquals("1", actual.get(0).get("x"));
        assertEquals("2", actual.get(0).get("y"));
    }

    @Test
    void testUnclosedQuoteAtFinish() throws CSVException {
        final CSVPushSession session = new CSVPushSession(CSVFormat.DEFAULT);
        assertTrue(session.feed("a,\"unclosed").isEmpty());
        assertThrows(CSVException.class, session::finish);
        assertTrue(session.isFailed());
        assertThrows(IllegalStateException.class, () -> session.feed("x"));
        assertThrows(IllegalStateException.class, session::finish);
    }

    @Test
    void testEmptyInput() throws CSVException {
        assertTrue(new CSVPushSession(CSVFormat.DEFAULT).finish().isEmpty());
        assertTrue(new CSVPushSession(CSVFormat.DEFAULT.builder().setHeader().get()).finish().isEmpty());
    }

    @Test
    void testTrailingRecordWithoutLineTerminator() throws CSVException {
        final CSVPushSession session = new CSVPushSession(CSVFormat.DEFAULT);
        assertTrue(session.feed("a,b").isEmpty());
        final List<CSVRecord> records = session.finish();
        assertEquals(1, records.size());
        assertEquals(Arrays.asList("a", "b"), records.get(0).toList());
    }

    @Test
    void testRepeatedFinish() throws CSVException {
        final CSVPushSession session = new CSVPushSession(CSVFormat.DEFAULT);
        session.feed("a,b\n");
        assertTrue(session.finish().isEmpty());
        assertTrue(session.finish().isEmpty(), "repeated finish is a no-op");
        assertThrows(IllegalStateException.class, () -> session.feed("c,d\n"), "feed after finish is rejected");
    }

    @Test
    void testUnsupportedFormatRejected() {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setMaxRows(2).get();
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new CSVPushSession(format));
        assertTrue(e.getMessage().contains("setMaxRows"), e.getMessage());
    }
}
