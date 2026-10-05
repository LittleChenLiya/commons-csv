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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link RecordTerminator} and the record terminator strategy.
 */
class RecordTerminatorTest {

    private static void assertMessageContains(final String message, final String... parts) {
        for (final String part : parts) {
            assertTrue(message.contains(part), "Expected message to contain <" + part + "> but was: " + message);
        }
    }

    @Test
    void testDefaultAcceptsMixedTerminators() throws IOException {
        final String data = "a,b\nc,d\re,f\r\n";
        try (CSVParser parser = CSVParser.parse(data, CSVFormat.DEFAULT)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(3, records.size());
            assertEquals("a", records.get(0).get(0));
            assertEquals("c", records.get(1).get(0));
            assertEquals("e", records.get(2).get(0));
        }
    }

    @Test
    void testLockFirstCrLfHeaderThenQuotedFieldWithEmbeddedLfThenBareLfIsRejected() throws IOException {
        final String data = "h1,h2\r\n" +
                "\"x\ny\",z\r\n" +
                "d,e\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            // The header and the quoted-field record are delivered.
            CSVRecord record = parser.nextRecord();
            assertNotNull(record);
            assertEquals("h1", record.get(0));
            record = parser.nextRecord();
            assertNotNull(record);
            assertEquals("x\ny", record.get(0));
            // The bare LF record is rejected before delivery.
            final IOException e = assertThrows(IOException.class, parser::nextRecord);
            assertInstanceOf(CSVException.class, e);
            assertMessageContains(e.getMessage(), "locked to CRLF", "found LF", "record 3", "position");
        }
    }

    @Test
    void testLockFirstLeadingLfCommentDoesNotChangeLock() throws IOException {
        final String data = "# comment\n" +
                "h1,h2\r\n" +
                "\"x\ny\",z\r\n" +
                "d,e\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setCommentMarker('#')
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            CSVRecord record = parser.nextRecord();
            assertNotNull(record);
            assertEquals("h1", record.get(0));
            record = parser.nextRecord();
            assertNotNull(record);
            assertEquals("x\ny", record.get(0));
            final IOException e = assertThrows(IOException.class, parser::nextRecord);
            assertInstanceOf(CSVException.class, e);
            assertMessageContains(e.getMessage(), "locked to CRLF", "found LF", "record 3");
        }
    }

    @Test
    void testLockFirstTrailingLfCommentDoesNotReject() throws IOException {
        final String data = "a,b\r\n" +
                "# trailing comment\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setCommentMarker('#')
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(1, records.size());
            assertEquals("a", records.get(0).get(0));
        }
    }

    @Test
    void testFixedLfRejectsCrLf() throws IOException {
        final String data = "a,b\r\nc,d\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.LF)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            // The first record uses CRLF, which is rejected before delivery.
            final IOException e = assertThrows(IOException.class, parser::nextRecord);
            assertInstanceOf(CSVException.class, e);
            assertMessageContains(e.getMessage(), "expected LF", "found CRLF", "record 1");
        }
    }

    @Test
    void testFixedCrLfRejectsLf() throws IOException {
        final String data = "a,b\nc,d\r\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.CRLF)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            final IOException e = assertThrows(IOException.class, parser::nextRecord);
            assertInstanceOf(CSVException.class, e);
            assertMessageContains(e.getMessage(), "expected CRLF", "found LF", "record 1");
        }
    }

    @Test
    void testFixedCrRejectsLf() throws IOException {
        final String data = "a,b\nc,d\r";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.CR)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            final IOException e = assertThrows(IOException.class, parser::nextRecord);
            assertInstanceOf(CSVException.class, e);
            assertMessageContains(e.getMessage(), "expected CR", "found LF", "record 1");
        }
    }

    @Test
    void testFixedCrAcceptsCr() throws IOException {
        final String data = "a,b\rc,d\r";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.CR)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(2, records.size());
            assertEquals("a", records.get(0).get(0));
            assertEquals("c", records.get(1).get(0));
        }
    }

    @Test
    void testEofWithoutTerminatorIsValid() throws IOException {
        final String data = "a,b";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(1, records.size());
            assertEquals("a", records.get(0).get(0));
        }
    }

    @Test
    void testChunkedCrLfRecognizedAsOne() throws IOException {
        final String data = "a,b\r\nc,d\r\ne,f\r\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.builder()
                .setReader(new ChunkedReader(data))
                .setFormat(format)
                .get()) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(3, records.size());
            assertEquals("a", records.get(0).get(0));
            assertEquals("c", records.get(1).get(0));
            assertEquals("e", records.get(2).get(0));
        }
    }

    @Test
    void testThreeRecordExample() throws IOException {
        final String data = "a,b\r\nc,d\r\ne,f\r\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(3, records.size());
            assertEquals("a", records.get(0).get(0));
            assertEquals("c", records.get(1).get(0));
            assertEquals("e", records.get(2).get(0));
            assertEquals("b", records.get(0).get(1));
            assertEquals("d", records.get(1).get(1));
            assertEquals("f", records.get(2).get(1));
        }
    }

    @Test
    void testHeaderCountsAsRecordAndLocks() throws IOException {
        // The header record's terminator is the one that locks the format; a later record using a different terminator is rejected.
        final String data = "h1,h2\r\na,b\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.parse(data, format)) {
            // The header is consumed during construction; the first data record uses LF and is rejected.
            final IOException e = assertThrows(IOException.class, parser::nextRecord);
            assertInstanceOf(CSVException.class, e);
            assertMessageContains(e.getMessage(), "locked to CRLF", "found LF", "record 1");
        }
    }

    @Test
    void testRecordTerminatorGetterAndToString() {
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setRecordTerminator(RecordTerminator.LOCK_FIRST)
                .get();
        assertEquals(RecordTerminator.LOCK_FIRST, format.getRecordTerminator());
        assertTrue(format.toString().contains("RecordTerminator=<LOCK_FIRST>"));
    }

    @Test
    void testRecordTerminatorRejectsNull() {
        assertThrows(NullPointerException.class, () -> CSVFormat.DEFAULT.builder().setRecordTerminator(null));
    }

    @Test
    void testDefaultRecordTerminatorIsAny() {
        assertEquals(RecordTerminator.ANY, CSVFormat.DEFAULT.getRecordTerminator());
    }

    @Test
    void testFixedTerminatorStrings() {
        assertEquals("\n", RecordTerminator.LF.getTerminator());
        assertEquals("\r\n", RecordTerminator.CRLF.getTerminator());
        assertEquals("\r", RecordTerminator.CR.getTerminator());
    }
}
