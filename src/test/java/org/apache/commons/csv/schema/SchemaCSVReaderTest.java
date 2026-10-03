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

package org.apache.commons.csv.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SchemaCSVReader}.
 */
class SchemaCSVReaderTest {

    private static List<SchemaRecord> readAll(final String csv, final CSVFormat format) throws IOException {
        final List<SchemaRecord> records = new ArrayList<>();
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), format)) {
            for (SchemaRecord record = reader.next(); record != null; record = reader.next()) {
                records.add(record);
            }
        }
        return records;
    }

    private static SchemaCSVException expectFailure(final String csv, final CSVFormat format) {
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), format)) {
            return assertThrows(SchemaCSVException.class, reader::next);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertSameError(final SchemaCSVException expected, final Throwable actual) {
        assertNotNull(actual);
        assertTrue(actual instanceof SchemaCSVException);
        final SchemaCSVException actualSchema = (SchemaCSVException) actual;
        assertEquals(expected.getError(), actualSchema.getError());
        assertEquals(expected.getSection(), actualSchema.getSection());
        assertEquals(expected.getRecordNumber(), actualSchema.getRecordNumber());
    }

    /** Acceptance case: two sections with reordered columns bind by name, not by position. */
    @Test
    void testTwoSectionsReorderedHeadersBindByName() throws IOException {
        final String csv = "__schema__,name,age\r\n"
                + "a,1\r\n"
                + "__schema__,age,name\r\n"
                + "2,b\r\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            final SchemaRecord first = reader.next();
            assertNotNull(first);
            assertEquals(1, first.getSection());
            assertEquals(2, first.getRecordNumber());
            assertEquals(1, first.getSectionRecordNumber());
            assertEquals("a", first.get("name"));
            assertEquals("1", first.get("age"));
            assertArrayEquals(new String[] {"a", "1"}, first.values());

            final SchemaRecord second = reader.next();
            assertNotNull(second);
            assertEquals(2, second.getSection());
            assertEquals(4, second.getRecordNumber());
            assertEquals(1, second.getSectionRecordNumber());
            // Reordered header: position 0 is now age, position 1 is name.
            assertEquals("b", second.get("name"));
            assertEquals("2", second.get("age"));
            assertArrayEquals(new String[] {"2", "b"}, second.values());
            assertEquals("2", second.get(0));
            assertEquals("b", second.get(1));

            assertNull(reader.next());
        }
    }

    /** Multiple data records in one section keep physical and per-section numbering. */
    @Test
    void testRecordNumberingAcrossSections() throws IOException {
        final String csv = "__schema__,c\n"
                + "x\n"
                + "y\n"
                + "__schema__,c\n"
                + "z\n";
        final List<SchemaRecord> records = readAll(csv, CSVFormat.DEFAULT);
        assertEquals(3, records.size());
        assertEquals(1, records.get(0).getSection());
        assertEquals(2, records.get(0).getRecordNumber());
        assertEquals(1, records.get(0).getSectionRecordNumber());
        assertEquals(1, records.get(1).getSection());
        assertEquals(3, records.get(1).getRecordNumber());
        assertEquals(2, records.get(1).getSectionRecordNumber());
        assertEquals(2, records.get(2).getSection());
        assertEquals(5, records.get(2).getRecordNumber());
        assertEquals(1, records.get(2).getSectionRecordNumber());
    }

    /** Data before the first declaration is a section-1 error. */
    @Test
    void testDataBeforeHeaderReportsSectionAndRecord() {
        final SchemaCSVException e = expectFailure("a,1\n", CSVFormat.DEFAULT);
        assertEquals(SchemaCSVError.DATA_BEFORE_HEADER, e.getError());
        assertEquals(1, e.getSection());
        assertEquals(1, e.getRecordNumber());
    }

    /** A duplicate column name in a declaration gives the declaration section and record. */
    @Test
    void testDuplicateColumnRejectsDeclaration() throws IOException {
        // The second declaration is the bad one: section 2, physical record 3.
        final String csv = "__schema__,name\n"
                + "a\n"
                + "__schema__,age,age\n"
                + "9,x\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            assertNotNull(reader.next()); // section 1 data
            final SchemaCSVException e = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.DUPLICATE_COLUMN, e.getError());
            assertEquals(2, e.getSection());
            assertEquals(3, e.getRecordNumber());
            // Terminated: the stale section-1 header cannot serve later records.
            assertTrue(reader.isTerminated());
            assertNull(reader.getHeaderNames());
            final SchemaCSVException again = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.TERMINATED, again.getError());
            assertSameError(e, again.getCause());
        }
    }

    /** Reusing the reserved name as a data column is a duplicate-column error. */
    @Test
    void testReservedNameCannotBeADataColumn() {
        final SchemaCSVException e = expectFailure("__schema__,__schema__,name\n1,a\n", CSVFormat.DEFAULT);
        assertEquals(SchemaCSVError.DUPLICATE_COLUMN, e.getError());
        assertEquals(1, e.getSection());
        assertEquals(1, e.getRecordNumber());
    }

    /** A wrong field count on a data record reports section and physical record. */
    @Test
    void testColumnCountMismatchReportsLocation() throws IOException {
        final String csv = "__schema__,name,age\n"
                + "a,1\n"
                + "only-one\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            assertNotNull(reader.next());
            final SchemaCSVException e = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.COLUMN_COUNT_MISMATCH, e.getError());
            assertEquals(1, e.getSection());
            assertEquals(3, e.getRecordNumber());
        }
    }

    /** After any failure, every subsequent next() keeps reporting termination. */
    @Test
    void testTerminalStatePersistsOnRepeatedNext() throws IOException {
        final String csv = "__schema__,name,age\n"
                + "a,1\n"
                + "bad\n"
                + "c,3\n"
                + "d,4\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            assertNotNull(reader.next());
            final SchemaCSVException first = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.COLUMN_COUNT_MISMATCH, first.getError());
            for (int i = 0; i < 3; i++) {
                final SchemaCSVException next = assertThrows(SchemaCSVException.class, reader::next);
                assertEquals(SchemaCSVError.TERMINATED, next.getError());
                assertSameError(first, next.getCause());
            }
            assertTrue(reader.isTerminated());
        }
    }

    /** A failed switch must not let following records bind with the previous header. */
    @Test
    void testBadDeclarationDoesNotReuseOldHeader() throws IOException {
        final String csv = "__schema__,name,age\n"
                + "a,1\n"
                + "__schema__,only,only\n"
                + "matches-old-arity,0\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            assertNotNull(reader.next());
            final SchemaCSVException bad = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.DUPLICATE_COLUMN, bad.getError());
            // Even though this record would fit the old two-column header, it is rejected as terminated.
            final SchemaCSVException after = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.TERMINATED, after.getError());
        }
    }

    /** An empty section (two adjacent declarations) is legal and needs no data records. */
    @Test
    void testEmptySectionIsAllowed() throws IOException {
        final String csv = "__schema__,name\n"
                + "a\n"
                + "__schema__,age\n"
                + "__schema__,name,age\n"
                + "b,2\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            final SchemaRecord first = reader.next();
            assertEquals("a", first.get("name"));
            // Declaration 2 opens section 2 (empty); declaration 3 opens section 3, which holds the data.
            final SchemaRecord second = reader.next();
            assertEquals(3, second.getSection());
            assertEquals(5, second.getRecordNumber());
            assertEquals(1, second.getSectionRecordNumber());
            assertEquals("b", second.get("name"));
            assertEquals("2", second.get("age"));
            assertNull(reader.next());
            assertEquals(3, reader.getSection());
        }
    }

    /** An empty input reaches EOF cleanly without a declaration. */
    @Test
    void testEmptyStreamReturnsNull() throws IOException {
        try (SchemaCSVReader reader = SchemaCSVReader.of("", CSVFormat.DEFAULT)) {
            assertNull(reader.next());
            assertTrue(!reader.isTerminated());
        }
    }

    /** Declarations only, ending at EOF, produce no data records and no error. */
    @Test
    void testOnlyDeclarationsEndingAtEof() throws IOException {
        try (SchemaCSVReader reader = SchemaCSVReader.of("__schema__,a\n__schema__,b\n", CSVFormat.DEFAULT)) {
            assertNull(reader.next());
            assertEquals(2, reader.getSection());
            assertEquals("b", reader.getHeaderNames().get(0));
        }
    }

    /** A bare marker declares zero columns; reaching EOF leaves an empty header installed. */
    @Test
    void testBareDeclarationInstallsEmptyHeader() throws IOException {
        try (SchemaCSVReader reader = SchemaCSVReader.of("__schema__\n", CSVFormat.DEFAULT)) {
            assertNull(reader.next());
            assertTrue(reader.getHeaderNames().isEmpty());
            assertEquals(1, reader.getSection());
        }
    }

    /** A declaration with no columns cannot accept a following data record. */
    @Test
    void testZeroColumnHeaderRejectsData() throws IOException {
        try (SchemaCSVReader reader = SchemaCSVReader.of("__schema__\nx\n", CSVFormat.DEFAULT)) {
            final SchemaCSVException e = assertThrows(SchemaCSVException.class, reader::next);
            assertEquals(SchemaCSVError.COLUMN_COUNT_MISMATCH, e.getError());
            assertEquals(1, e.getSection());
            assertEquals(2, e.getRecordNumber());
        }
    }

    /** Comment lines are parsed by CSVFormat, never delivered as data. */
    @Test
    void testCommentsAreParsedByFormatAndSkipped() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();
        final String csv = "# leading comment\n"
                + "__schema__,name,age\n"
                + "# mid comment\n"
                + "a,1\n"
                + "# trailer comment\n";
        final List<SchemaRecord> records = readAll(csv, format);
        assertEquals(1, records.size());
        assertEquals("a", records.get(0).get("name"));
        assertEquals("mid comment", records.get(0).getComment());
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, format)) {
            reader.next();
            assertNull(reader.next());
            assertEquals("trailer comment", reader.getTrailerComment());
        }
    }

    /** Quoting is handled by the underlying parser, including embedded delimiters and newlines. */
    @Test
    void testQuotedFieldsAreDecodedByFormat() throws IOException {
        final String csv = "__schema__,name,note\n"
                + "\"Smith, John\",\"line1\nline2\"\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            final SchemaRecord record = reader.next();
            assertEquals("Smith, John", record.get("name"));
            assertEquals("line1\nline2", record.get("note"));
        }
    }

    /**
     * Marker identity is the decoded value: quoting the exact marker keeps it a declaration,
     * while a decoded leading space does not.
     */
    @Test
    void testMarkerIdentityUsesDecodedField() throws IOException {
        // Quoted marker: still a declaration.
        try (SchemaCSVReader reader = SchemaCSVReader.of("\"__schema__\",name\na\n", CSVFormat.DEFAULT)) {
            final SchemaRecord record = reader.next();
            assertEquals("a", record.get("name"));
        }
        // Leading space in the decoded value is data, not a marker; with trim disabled it has no header.
        final SchemaCSVException e = expectFailure("\" __schema__\",name\na,1\n", CSVFormat.DEFAULT);
        assertEquals(SchemaCSVError.DATA_BEFORE_HEADER, e.getError());
        // With surrounding-space trimming enabled, the decoded (trimmed) value becomes the marker.
        final CSVFormat trimmed = CSVFormat.DEFAULT.builder().setIgnoreSurroundingSpaces(true).get();
        try (SchemaCSVReader reader = SchemaCSVReader.of(" __schema__ ,name\na\n", trimmed)) {
            assertEquals("a", reader.next().get("name"));
        }
    }

    /** The iterator view wraps the located schema exception in an UncheckedIOException. */
    @Test
    void testIteratorWrapsSchemaException() throws IOException {
        try (SchemaCSVReader reader = SchemaCSVReader.of("x,y\n", CSVFormat.DEFAULT)) {
            final java.util.Iterator<SchemaRecord> it = reader.iterator();
            final UncheckedIOException ue = assertThrows(UncheckedIOException.class, it::hasNext);
            assertTrue(ue.getCause() instanceof SchemaCSVException);
            final SchemaCSVException cause = (SchemaCSVException) ue.getCause();
            assertEquals(SchemaCSVError.DATA_BEFORE_HEADER, cause.getError());
            assertEquals(1, cause.getSection());
            assertEquals(1, cause.getRecordNumber());
            assertTrue(reader.isTerminated());
        }
    }

    /** next() and the iterator share the same stream position (look-ahead cache). */
    @Test
    void testIteratorAndNextSharePosition() throws IOException {
        final String csv = "__schema__,name\na\n__schema__,name\nb\n";
        try (SchemaCSVReader reader = SchemaCSVReader.of(csv, CSVFormat.DEFAULT)) {
            assertEquals("a", reader.next().get("name"));
            final java.util.Iterator<SchemaRecord> it = reader.iterator();
            assertTrue(it.hasNext());
            final SchemaRecord b = it.next();
            assertEquals(2, b.getSection());
            assertEquals("b", b.get("name"));
            assertFalse(it.hasNext());
            assertNull(reader.next());
        }
    }

    /** Section 1 starts before any declaration; numbering advances on each declaration consumed. */
    @Test
    void testSectionAdvancesWithDeclarations() throws IOException {
        final String csv = "__schema__,a\n1\n__schema__,b\n2\n__schema__,c\n3\n";
        final List<SchemaRecord> records = readAll(csv, CSVFormat.DEFAULT);
        assertEquals(1, records.get(0).getSection());
        assertEquals("1", records.get(0).get("a"));
        assertEquals(2, records.get(1).getSection());
        assertEquals("2", records.get(1).get("b"));
        assertEquals(3, records.get(2).getSection());
        assertEquals("3", records.get(2).get("c"));
    }
}
