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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SchemaCSVReader}.
 */
class SchemaCSVReaderTest {

    /**
     * The acceptance scenario: after {@code __schema__,name,age} read {@code a,1}, then
     * after {@code __schema__,age,name} read {@code 2,b}; each record binds to its own
     * section's header.
     */
    @Test
    void testSectionSwitchingBindsToCurrentHeader() throws IOException {
        final String csv = "__schema__,name,age\n"
                + "a,1\n"
                + "__schema__,age,name\n"
                + "2,b\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            final SchemaRecord first = reader.next();
            assertEquals(1, first.getSectionNumber());
            assertEquals("a", first.get("name"));
            assertEquals("1", first.get("age"));
            assertEquals("a", first.get(0));
            assertEquals("1", first.get(1));
            assertEquals("name", first.getHeaderNames().get(0));
            assertEquals("age", first.getHeaderNames().get(1));

            final SchemaRecord second = reader.next();
            assertEquals(2, second.getSectionNumber());
            assertEquals("2", second.get("age"));
            assertEquals("b", second.get("name"));
            assertEquals("2", second.get(0));
            assertEquals("b", second.get(1));

            assertFalse(reader.hasNext());
        }
    }

    /**
     * A quoted {@code "__schema__"} still declares a section: identity is by decoded field
     * value, not by reference.
     */
    @Test
    void testQuotedSchemaMarkerDeclaresSection() throws IOException {
        final String csv = "\"__schema__\",a,b\n1,2\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            final SchemaRecord record = reader.next();
            assertEquals(1, record.getSectionNumber());
            assertEquals("1", record.get("a"));
            assertEquals("2", record.get("b"));
            assertFalse(reader.hasNext());
        }
    }

    /**
     * A data record before any declaration terminates the reader; next() keeps reporting
     * the termination.
     */
    @Test
    void testDataBeforeSchemaTerminates() throws IOException {
        final String csv = "a,1\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            assertFalse(reader.hasNext());
            assertTrue(reader.isTerminated());
            final SchemaCSVException error = reader.getTerminalError();
            assertNotNull(error);
            assertEquals(0, error.getSectionNumber());
            // Every next() call keeps reporting the termination.
            assertThrows(NoSuchElementException.class, reader::next);
            assertThrows(NoSuchElementException.class, reader::next);
        }
    }

    /**
     * A declaration with a duplicate column name terminates the reader; next() keeps
     * reporting the termination.
     */
    @Test
    void testDuplicateColumnNameTerminates() throws IOException {
        final String csv = "__schema__,a,a\n1,2\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            assertFalse(reader.hasNext());
            assertTrue(reader.isTerminated());
            final SchemaCSVException error = reader.getTerminalError();
            assertNotNull(error);
            assertEquals(1, error.getSectionNumber());
            assertThrows(NoSuchElementException.class, reader::next);
            assertThrows(NoSuchElementException.class, reader::next);
        }
    }

    /**
     * A data record whose field count does not match the section header terminates the
     * reader.
     */
    @Test
    void testColumnCountMismatchTerminates() throws IOException {
        final String csv = "__schema__,a,b\n1\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            assertFalse(reader.hasNext());
            assertTrue(reader.isTerminated());
            final SchemaCSVException error = reader.getTerminalError();
            assertNotNull(error);
            assertEquals(1, error.getSectionNumber());
            assertThrows(NoSuchElementException.class, reader::next);
        }
    }

    /**
     * An empty section (a declaration followed immediately by another declaration) produces
     * no data record but does not terminate.
     */
    @Test
    void testEmptySection() throws IOException {
        final String csv = "__schema__,a\n"
                + "__schema__,b\n"
                + "1\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            final SchemaRecord record = reader.next();
            assertEquals(2, record.getSectionNumber());
            assertEquals("1", record.get("b"));
            assertFalse(reader.hasNext());
            assertFalse(reader.isTerminated());
        }
    }

    /**
     * Comments are parsed per the configured format and attached to the following record.
     */
    @Test
    void testCommentsParsedPerFormat() throws IOException {
        final CSVFormat format = CSVFormat.Builder.create().setCommentMarker('#').build();
        final String csv = "# leading comment\n"
                + "__schema__,a\n"
                + "# data comment\n"
                + "1\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), format)) {
            final SchemaRecord record = reader.next();
            assertEquals(1, record.getSectionNumber());
            assertEquals("1", record.get("a"));
            assertEquals("data comment", record.getComment());
            assertFalse(reader.hasNext());
        }
    }

    /**
     * A comment that precedes a schema declaration is carried over to the first data
     * record of the new section.
     */
    @Test
    void testCommentBeforeSchemaCarriedOver() throws IOException {
        final CSVFormat format = CSVFormat.Builder.create().setCommentMarker('#').build();
        final String csv = "# section comment\n"
                + "__schema__,a\n"
                + "1\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), format)) {
            final SchemaRecord record = reader.next();
            assertEquals("section comment", record.getComment());
        }
    }

    /**
     * End of stream immediately after a declaration: no records, no error.
     */
    @Test
    void testEofAfterSchema() throws IOException {
        final String csv = "__schema__,a,b\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            assertFalse(reader.hasNext());
            assertFalse(reader.isTerminated());
            assertNull(reader.getTerminalError());
        }
    }

    /**
     * End of stream with a trailing comment after the last record.
     */
    @Test
    void testEofWithTrailingComment() throws IOException {
        final CSVFormat format = CSVFormat.Builder.create().setCommentMarker('#').build();
        final String csv = "__schema__,a\n"
                + "1\n"
                + "# trailing comment\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), format)) {
            final SchemaRecord record = reader.next();
            assertEquals("1", record.get("a"));
            assertFalse(reader.hasNext());
            assertFalse(reader.isTerminated());
        }
    }

    /**
     * A declaration with no column names is invalid and terminates the reader.
     */
    @Test
    void testSchemaWithoutColumnsTerminates() throws IOException {
        final String csv = "__schema__\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            assertFalse(reader.hasNext());
            assertTrue(reader.isTerminated());
            assertEquals(1, reader.getTerminalError().getSectionNumber());
            assertThrows(NoSuchElementException.class, reader::next);
        }
    }

    /**
     * Schema declaration records are consumed and never delivered to business code.
     */
    @Test
    void testGetRecordsDeliversOnlyDataRecords() throws IOException {
        final String csv = "__schema__,a\n"
                + "1\n"
                + "__schema__,b\n"
                + "2\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            final List<SchemaRecord> records = reader.getRecords();
            assertEquals(2, records.size());
            assertEquals(1, records.get(0).getSectionNumber());
            assertEquals("1", records.get(0).get("a"));
            assertEquals(2, records.get(1).getSectionNumber());
            assertEquals("2", records.get(1).get("b"));
        }
    }

    /**
     * A format that defines a header is rejected because headers are owned by the sections.
     */
    @Test
    void testFormatWithHeaderRejected() throws IOException {
        final CSVFormat format = CSVFormat.Builder.create().setHeader("x", "y").build();
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader("a,b\n1,2\n"), format)) {
            fail("Expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * toMap() exposes the field values keyed by the section's column names.
     */
    @Test
    void testToMap() throws IOException {
        final String csv = "__schema__,name,age\n"
                + "a,1\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            final SchemaRecord record = reader.next();
            assertEquals("{name=a, age=1}", record.toMap().toString());
            assertTrue(record.isMapped("name"));
            assertFalse(record.isMapped("missing"));
            assertThrows(IllegalArgumentException.class, () -> record.get("missing"));
        }
    }

    /**
     * A quoted empty field is a real value and is bound like any other field.
     */
    @Test
    void testQuotedEmptyFieldIsValue() throws IOException {
        final String csv = "__schema__,a,b\n"
                + "\"\",2\n";
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            final SchemaRecord record = reader.next();
            assertEquals("", record.get("a"));
            assertEquals("2", record.get("b"));
        }
    }
}
