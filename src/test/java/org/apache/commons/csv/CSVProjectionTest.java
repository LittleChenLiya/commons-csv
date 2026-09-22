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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVProjection}, {@link ProjectedRecord} and {@link CSVProjectionException}.
 */
class CSVProjectionTest {

    private CSVParser parser(final String input) throws Exception {
        return CSVFormat.DEFAULT.builder().setHeader().get().parse(new StringReader(input));
    }

    @Test
    void testDuplicateHeaderWithOccurrence() throws Exception {
        try (CSVParser p = parser("id,name,name\n1,A,B\n")) {
            final CSVProjection projection = CSVProjection.builder().select("id").select("name", 1, "secondName").compile(p);
            final ProjectedRecord record = projection.project(p.iterator().next());
            assertEquals("B", record.get("secondName"));
            assertEquals("1", record.get("id"));
        }
    }

    @Test
    void testDuplicateHeaderWithoutOccurrenceIsAmbiguous() throws Exception {
        try (CSVParser p = parser("id,name,name\n1,A,B\n")) {
            final CSVProjectionException e = assertThrows(CSVProjectionException.class,
                    () -> CSVProjection.builder().select("name").compile(p));
            assertEquals("name", e.getColumnName());
            assertEquals("name", e.getAlias());
            assertEquals(-1, e.getRecordNumber());
        }
    }

    @Test
    void testMissingColumnVersusEmptyValue() throws Exception {
        // "1,A" misses the third column; "1,A," has it but empty.
        try (CSVParser p = parser("id,name,extra\n1,A\n1,A,\n")) {
            final CSVProjection projection = CSVProjection.builder().select("id").select("extra")
                    .onMissingColumn(CSVProjection.MissingColumnPolicy.DEFAULT, "D")
                    .onEmptyValue(CSVProjection.EmptyValuePolicy.DEFAULT, "D").compile(p);
            final List<ProjectedRecord> records = new ArrayList<>();
            for (final CSVRecord r : p) { records.add(projection.project(r)); }
            assertEquals(2, records.size());
            // Missing column: default applies.
            assertEquals("D", records.get(0).get("extra"));
            // Empty string: the configured empty-value DEFAULT applies.
            assertEquals("D", records.get(1).get("extra"));
        }
        // Default empty policy KEEP: empty string is preserved, a missing-column default must not override it.
        try (CSVParser p = parser("id,name,extra\n1,A\n1,A,\n")) {
            final CSVProjection projection = CSVProjection.builder().select("id").select("extra")
                    .onMissingColumn(CSVProjection.MissingColumnPolicy.DEFAULT, "D").compile(p);
            final List<ProjectedRecord> records = new ArrayList<>();
            for (final CSVRecord r : p) { records.add(projection.project(r)); }
            assertEquals("D", records.get(0).get("extra"));
            assertEquals("", records.get(1).get("extra"));
        }
    }

    @Test
    void testMissingHeaderPolicies() throws Exception {
        try (CSVParser p = parser("id,name\n1,A\n")) {
            assertThrows(CSVProjectionException.class, () -> CSVProjection.builder().select("missing").compile(p));
        }
        try (CSVParser p = parser("id,name\n1,A\n")) {
            final CSVProjection projection = CSVProjection.builder().select("missing", "m")
                    .onMissingHeader(CSVProjection.MissingHeaderPolicy.NULL).compile(p);
            assertNull(projection.project(p.iterator().next()).get("m"));
        }
        try (CSVParser p = parser("id,name\n1,A\n")) {
            final CSVProjection projection = CSVProjection.builder().select("missing", "m")
                    .onMissingHeader(CSVProjection.MissingHeaderPolicy.DEFAULT, "N/A").compile(p);
            assertEquals("N/A", projection.project(p.iterator().next()).get("m"));
        }
    }

    @Test
    void testMissingColumnErrorCarriesContext() throws Exception {
        try (CSVParser p = parser("id,name,extra\n1,A\n")) {
            final CSVProjection projection = CSVProjection.builder().select("extra", "x").compile(p);
            final CSVRecord record = p.iterator().next();
            final CSVProjectionException e = assertThrows(CSVProjectionException.class, () -> projection.project(record));
            assertEquals(1, e.getRecordNumber());
            assertEquals("extra", e.getColumnName());
            assertEquals("x", e.getAlias());
        }
    }

    @Test
    void testEmptyValueErrorPolicy() throws Exception {
        try (CSVParser p = parser("id,name\n1,\n")) {
            final CSVProjection projection = CSVProjection.builder().select("name")
                    .onEmptyValue(CSVProjection.EmptyValuePolicy.ERROR).compile(p);
            final CSVRecord record = p.iterator().next();
            final CSVProjectionException e = assertThrows(CSVProjectionException.class, () -> projection.project(record));
            assertEquals(1, e.getRecordNumber());
            assertEquals("name", e.getColumnName());
        }
    }

    @Test
    void testQuotedNewlineValue() throws Exception {
        try (CSVParser p = parser("id,name\n1,\"x\ny\"\n")) {
            final CSVProjection projection = CSVProjection.builder().select("name").compile(p);
            final ProjectedRecord record = projection.project(p.iterator().next());
            assertEquals("x\ny", record.get("name"));
        }
    }

    @Test
    void testHeaderOnly() throws Exception {
        try (CSVParser p = parser("id,name\n")) {
            final CSVProjection projection = CSVProjection.builder().select("id").select("name").compile(p);
            long count = 0;
            for (final CSVRecord record : p) {
                projection.project(record);
                count++;
            }
            assertEquals(0, count);
        }
    }

    @Test
    void testCaseSensitivity() throws Exception {
        try (CSVParser p = parser("Id,Name\n1,A\n")) {
            // Exact matching is the default: different case does not match.
            assertThrows(CSVProjectionException.class, () -> CSVProjection.builder().select("id").compile(p));
        }
        try (CSVParser p = parser("Id,Name\n1,A\n")) {
            final CSVProjection projection = CSVProjection.builder().select("id").select("NAME")
                    .caseSensitivity(CSVProjection.CaseSensitivity.IGNORE_CASE).compile(p);
            final ProjectedRecord record = projection.project(p.iterator().next());
            assertEquals("1", record.get("id"));
            assertEquals("A", record.get("NAME"));
        }
    }

    @Test
    void testCaseInsensitiveDuplicateIsAmbiguous() throws Exception {
        try (CSVParser p = parser("id,ID\n1,2\n")) {
            assertThrows(CSVProjectionException.class, () -> CSVProjection.builder().select("id")
                    .caseSensitivity(CSVProjection.CaseSensitivity.IGNORE_CASE).compile(p));
        }
    }

    @Test
    void testIllegalAlias() {
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().select("a", "x").select("b", "x"));
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().select("a", ""));
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().select("a").select("a", 1));
    }

    @Test
    void testCompileFailureDoesNotConsumeFirstRecord() throws Exception {
        try (CSVParser p = parser("id,name\n1,A\n2,B\n")) {
            assertThrows(CSVProjectionException.class, () -> CSVProjection.builder().select("missing").compile(p));
            final CSVRecord first = p.iterator().next();
            assertEquals(1, first.getRecordNumber());
            assertEquals("1", first.get(0));
        }
    }

    @Test
    void testStreamingCountAndSourceMetadata() throws Exception {
        final StringBuilder input = new StringBuilder("id,name,name\n");
        for (int i = 0; i < 1000; i++) {
            input.append(i).append(',').append('v').append(i).append(',').append('w').append(i).append('\n');
        }
        try (CSVParser p = parser(input.toString())) {
            final CSVProjection projection = CSVProjection.builder().select("id").select("name", 1, "second").compile(p);
            long count = 0;
            for (final CSVRecord record : p) {
                final ProjectedRecord projected = projection.project(record);
                // Value semantics identical to CSVRecord.
                assertEquals(record.get(0), projected.get("id"));
                assertEquals(record.get(2), projected.get("second"));
                // Original record number and positions are preserved.
                assertEquals(record.getRecordNumber(), projected.getRecordNumber());
                assertEquals(record.getCharacterPosition(), projected.getCharacterPosition());
                assertEquals(record.getBytePosition(), projected.getBytePosition());
                count++;
            }
            assertEquals(1000, count);
        }
    }

    @Test
    void testAliasLookupAndMap() throws Exception {
        try (CSVParser p = parser("id,name\n1,A\n")) {
            final CSVProjection projection = CSVProjection.builder().select("name", "n").compile(p);
            final ProjectedRecord record = projection.project(p.iterator().next());
            assertEquals("A", record.get("n"));
            assertEquals("A", record.get(0));
            assertEquals(1, record.size());
            assertEquals("A", record.toMap().get("n"));
            assertEquals("n", record.aliases().get(0));
            assertThrows(IllegalArgumentException.class, () -> record.get("unknown"));
        }
    }

    @Test
    void testNoExtraTrimmingOfValues() throws Exception {
        try (CSVParser p = parser("id,name\n1,  A  \n")) {
            final CSVProjection projection = CSVProjection.builder().select("name").compile(p);
            // Whitespace handling is governed by CSVFormat only; the projection does not trim business values.
            assertEquals("  A  ", projection.project(p.iterator().next()).get("name"));
        }
    }

    @Test
    void testNegativeOccurrenceRejected() {
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().select("a", -1));
    }

    @Test
    void testOccurrenceOutOfRangeUsesMissingHeaderPolicy() throws Exception {
        try (CSVParser p = parser("id,name\n1,A\n")) {
            assertThrows(CSVProjectionException.class, () -> CSVProjection.builder().select("name", 1).compile(p));
            final CSVProjection projection = CSVProjection.builder().select("name", 1, "n")
                    .onMissingHeader(CSVProjection.MissingHeaderPolicy.DEFAULT, "D").compile(p);
            assertEquals("D", projection.project(p.iterator().next()).get("n"));
        }
    }

    @Test
    void testNullStringValuePassesThrough() throws Exception {
        try (CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setNullString("NULL").get()
                .parse(new StringReader("id,name\n1,NULL\n"))) {
            final CSVProjection projection = CSVProjection.builder().select("name").compile(p);
            // Same value semantics as CSVRecord: the null string maps to null.
            assertNull(projection.project(p.iterator().next()).get("name"));
        }
    }

    @Test
    void testAliasesUniqueAndOrdered() throws Exception {
        try (CSVParser p = parser("a,b,c\n1,2,3\n")) {
            final CSVProjection projection = CSVProjection.builder().select("c", "z").select("a", "y").compile(p);
            assertEquals(java.util.Arrays.asList("z", "y"), projection.getAliases());
            final ProjectedRecord record = projection.project(p.iterator().next());
            assertEquals("3", record.get("z"));
            assertEquals("1", record.get("y"));
            assertTrue(record.getSourceRecord() != null);
        }
    }
}
