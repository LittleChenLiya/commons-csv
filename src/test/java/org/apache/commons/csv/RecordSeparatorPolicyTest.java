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
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link RecordSeparatorPolicy} enforcement while parsing.
 */
class RecordSeparatorPolicyTest {

    private static CSVFormat format(final RecordSeparatorPolicy policy) {
        return CSVFormat.DEFAULT.builder().setRecordSeparatorPolicy(policy).get();
    }

    private static List<CSVRecord> parse(final String input, final CSVFormat format) throws IOException {
        final List<CSVRecord> records = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(input, format)) {
            CSVRecord record;
            while ((record = parser.nextRecord()) != null) {
                records.add(record);
            }
        }
        return records;
    }

    @Test
    void testDefaultIsAnyAndAcceptsMixedTerminators() throws IOException {
        assertEquals(RecordSeparatorPolicy.ANY, CSVFormat.DEFAULT.getRecordSeparatorPolicy());
        final List<CSVRecord> records = parse("a,b\nc,d\r\ne,f\rg,h", CSVFormat.DEFAULT);
        assertEquals(4, records.size());
        assertEquals("h", records.get(3).get(1));
    }

    @Test
    void testBuilderNullResetsToAny() {
        assertEquals(RecordSeparatorPolicy.ANY, CSVFormat.DEFAULT.builder()
                .setRecordSeparatorPolicy(RecordSeparatorPolicy.CRLF).setRecordSeparatorPolicy(null).get().getRecordSeparatorPolicy());
    }

    @Test
    void testConsistentLocksOnCrlfHeaderAndRejectsBareLfRecord() throws IOException {
        // CRLF header, then a quoted field embedding a bare LF (part of the value, not a record separator),
        // then a record ended by a bare LF which must be rejected before that record is delivered.
        final String input = "a,b\r\n\"x\ny\",2\r\n3,4\n5,6\r\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder().setRecordSeparatorPolicy(RecordSeparatorPolicy.CONSISTENT).setHeader().get();
        try (CSVParser parser = CSVParser.parse(input, format)) {
            // The header record already locked the policy to CRLF while the parser was constructed.
            final CSVRecord first = parser.nextRecord();
            assertEquals(1, first.getRecordNumber());
            assertEquals("x\ny", first.get(0));
            assertEquals("2", first.get(1));
            final CSVException e = assertThrows(CSVException.class, parser::nextRecord);
            assertTrue(e.getMessage().contains("record 2"), e.getMessage());
            assertTrue(e.getMessage().contains("character position 18"), e.getMessage());
            assertTrue(e.getMessage().contains("expected CRLF but found LF"), e.getMessage());
        }
    }

    @Test
    void testConsistentLocksOnFirstLfAndRejectsCrlf() {
        final CSVFormat format = format(RecordSeparatorPolicy.CONSISTENT);
        final CSVException e = assertThrows(CSVException.class, () -> parse("a,b\n1,2\r\n", format));
        assertTrue(e.getMessage().contains("expected LF but found CRLF"), e.getMessage());
    }

    @Test
    void testLeadingLfCommentDoesNotLockConsistentPolicy() throws IOException {
        // A comment line terminated by a bare LF must not lock the format; the CRLF header locks it instead.
        final CSVFormat format = CSVFormat.DEFAULT.builder().setRecordSeparatorPolicy(RecordSeparatorPolicy.CONSISTENT)
                .setCommentMarker('#').setHeader().get();
        try (CSVParser parser = CSVParser.parse("# comment ended by bare LF\na,b\r\n1,2\r\n3,4\r\n", format)) {
            assertEquals("comment ended by bare LF", parser.getHeaderComment().trim());
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(2, records.size());
            assertEquals("1", records.get(0).get(0));
            assertEquals("4", records.get(1).get(1));
        }
    }

    @Test
    void testCommentDoesNotLockAndCrlfCommentBeforeLfRecords() throws IOException {
        // A CRLF-terminated comment must not lock either; the first real record (LF) locks.
        final CSVFormat format = CSVFormat.DEFAULT.builder().setRecordSeparatorPolicy(RecordSeparatorPolicy.CONSISTENT)
                .setCommentMarker('#').get();
        final List<CSVRecord> records = parse("# c\r\na,b\n1,2\n", format);
        assertEquals(2, records.size());
        assertEquals("2", records.get(1).get(1));
    }

    @Test
    void testChunkedCrLfRecognizedAsSingleTerminator() throws IOException {
        // ChunkedReader hands out one character per read, so CR and LF always arrive in separate chunks;
        // they must still be recognized as one CRLF terminator. Three records, all CRLF-terminated.
        final CSVFormat format = format(RecordSeparatorPolicy.CRLF);
        try (CSVParser parser = CSVParser.parse(new ChunkedReader("a,b\r\n1,2\r\n3,4\r\n"), format)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(3, records.size());
            assertEquals("a", records.get(0).get(0));
            assertEquals("2", records.get(1).get(1));
            assertEquals("4", records.get(2).get(1));
        }
    }

    @Test
    void testChunkedCrLfSplitInsideConsistentPolicy() throws IOException {
        final CSVFormat format = format(RecordSeparatorPolicy.CONSISTENT);
        try (CSVParser parser = CSVParser.parse(new ChunkedReader("a,b\r\n1,2\r\n3,4"), format)) {
            assertEquals(3, parser.getRecords().size());
        }
    }

    @Test
    void testFixedLfPolicy() throws IOException {
        final CSVFormat format = format(RecordSeparatorPolicy.LF);
        assertEquals(2, parse("a,b\n1,2\n", format).size());
        assertThrows(CSVException.class, () -> parse("a,b\r\n", format));
        assertThrows(CSVException.class, () -> parse("a,b\r", format));
    }

    @Test
    void testFixedCrPolicy() throws IOException {
        final CSVFormat format = format(RecordSeparatorPolicy.CR);
        assertEquals(2, parse("a,b\r1,2\r", format).size());
        assertThrows(CSVException.class, () -> parse("a,b\n", format));
        assertThrows(CSVException.class, () -> parse("a,b\r\n", format));
    }

    @Test
    void testFixedCrlfPolicyRejectsBareLfAndBareCr() {
        final CSVFormat format = format(RecordSeparatorPolicy.CRLF);
        CSVException e = assertThrows(CSVException.class, () -> parse("a,b\n", format));
        assertTrue(e.getMessage().contains("expected CRLF but found LF"), e.getMessage());
        e = assertThrows(CSVException.class, () -> parse("a,b\rc,d\r\n", format));
        assertTrue(e.getMessage().contains("expected CRLF but found CR"), e.getMessage());
    }

    @Test
    void testQuotedEmbeddedLineBreaksNeverParticipate() throws IOException {
        // Even under a fixed CRLF policy, bare LF and CR inside quoted values are content, not record separators.
        final CSVFormat format = format(RecordSeparatorPolicy.CRLF);
        final List<CSVRecord> records = parse("a,b\r\n\"x\ny\",\"p\rq\"\r\n", format);
        assertEquals(2, records.size());
        assertEquals("x\ny", records.get(1).get(0));
        assertEquals("p\rq", records.get(1).get(1));
    }

    @Test
    void testEofWithoutTerminatorIsLegal() throws IOException {
        final CSVFormat format = format(RecordSeparatorPolicy.CRLF);
        final List<CSVRecord> records = parse("a,b\r\n1,2", format);
        assertEquals(2, records.size());
        assertEquals("2", records.get(1).get(1));
    }

    @Test
    void testViolationFailsBeforeRecordDelivery() throws IOException {
        final CSVFormat format = format(RecordSeparatorPolicy.LF);
        try (CSVParser parser = CSVParser.parse(new StringReader("a,b\n1,2\r\n"), format)) {
            final CSVRecord first = parser.nextRecord();
            assertEquals(1, first.getRecordNumber());
            // The offending record is never delivered: the lexer fails while it is being read.
            assertThrows(CSVException.class, parser::nextRecord);
            assertEquals(1, parser.getRecordNumber());
        }
    }
}
