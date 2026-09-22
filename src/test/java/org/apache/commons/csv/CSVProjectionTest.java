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

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVProjection.CasePolicy;
import org.apache.commons.csv.CSVProjection.EmptyPolicy;
import org.apache.commons.csv.CSVProjection.MissingPolicy;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVProjection} and {@link ProjectedRecord}.
 */
class CSVProjectionTest {

    private CSVParser parse(final String csv) throws IOException {
        return CSVFormat.DEFAULT.builder().setHeader().get().parse(new StringReader(csv));
    }

    @Test
    void testDuplicateHeaderWithOccurrence() throws IOException {
        try (CSVParser parser = parse("id,name,name\n1,A,B\n")) {
            final CSVProjection projection = CSVProjection.builder().column("id").column("name", 1, "secondName").compile(parser);
            final ProjectedRecord record = projection.project(parser.iterator().next());
            assertEquals("1", record.get("id"));
            assertEquals("B", record.get("secondName"));
            assertEquals("B", record.get(1));
        }
    }

    @Test
    void testDuplicateHeaderWithoutOccurrenceIsAmbiguous() throws IOException {
        try (CSVParser parser = parse("id,name,name\n1,A,B\n")) {
            final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CSVProjection.builder().column("name").compile(parser));
            assertTrue(e.getMessage().contains("Ambiguous"));
        }
    }

    @Test
    void testMissingColumnDefaultDoesNotOverrideEmptyString() throws IOException {
        try (CSVParser parser = parse("id,name,note\n1,A\n1,A,\n")) {
            final CSVProjection projection = CSVProjection.builder().column("id").column("note")
                    .onMissingColumn(MissingPolicy.DEFAULT, "N/A").compile(parser);
            final Iterator<CSVRecord> iterator = parser.iterator();
            // Short record: the column is missing, the default applies.
            assertEquals("N/A", projection.project(iterator.next()).get("note"));
            // Trailing empty field: the column exists and is empty, preserved as-is.
            assertEquals("", projection.project(iterator.next()).get("note"));
        }
    }

    @Test
    void testMissingColumnErrorCarriesContext() throws IOException {
        try (CSVParser parser = parse("id,name\n1\n")) {
            final CSVProjection projection = CSVProjection.builder().column("name", "theName").compile(parser);
            final CSVProjectionException e = assertThrows(CSVProjectionException.class,
                    () -> projection.project(parser.iterator().next()));
            assertEquals(1L, e.getRecordNumber());
            assertEquals("name", e.getColumnName());
            assertEquals("theName", e.getAlias());
        }
    }

    @Test
    void testMissingColumnNullPolicy() throws IOException {
        try (CSVParser parser = parse("id,name\n1\n")) {
            final CSVProjection projection = CSVProjection.builder().column("name").onMissingColumn(MissingPolicy.NULL).compile(parser);
            assertNull(projection.project(parser.iterator().next()).get("name"));
        }
    }

    @Test
    void testMissingHeaderPolicies() throws IOException {
        try (CSVParser parser = parse("id,name\n1,A\n")) {
            assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("missing").compile(parser));
            final CSVProjection nullProjection = CSVProjection.builder().column("missing").onMissingHeader(MissingPolicy.NULL)
                    .compile(parser);
            assertNull(nullProjection.project(parser.getRecords().get(0)).get("missing"));
        }
        try (CSVParser parser = parse("id,name\n1,A\n")) {
            final CSVProjection defaultProjection = CSVProjection.builder().column("missing")
                    .onMissingHeader(MissingPolicy.DEFAULT, "dflt").compile(parser);
            assertEquals("dflt", defaultProjection.project(parser.getRecords().get(0)).get("missing"));
        }
    }

    @Test
    void testEmptyValuePolicies() throws IOException {
        try (CSVParser parser = parse("id,name\n1,\n")) {
            final CSVRecord record = parser.iterator().next();
            assertEquals("", CSVProjection.builder().column("name").compile("id", "name").project(record).get("name"));
            assertNull(CSVProjection.builder().column("name").onEmpty(EmptyPolicy.NULL).compile("id", "name").project(record).get("name"));
            assertEquals("x", CSVProjection.builder().column("name").onEmpty(EmptyPolicy.DEFAULT, "x").compile("id", "name").project(record)
                    .get("name"));
            assertThrows(CSVProjectionException.class,
                    () -> CSVProjection.builder().column("name").onEmpty(EmptyPolicy.ERROR).compile("id", "name").project(record));
        }
    }

    @Test
    void testQuotedNewline() throws IOException {
        try (CSVParser parser = parse("id,name\n1,\"a\nb\"\n2,c\n")) {
            final CSVProjection projection = CSVProjection.builder().column("name").compile(parser);
            final List<ProjectedRecord> records = parser.stream().map(projection::project).collect(Collectors.toList());
            assertEquals(2, records.size());
            assertEquals("a\nb", records.get(0).get("name"));
            assertEquals(1L, records.get(0).getRecordNumber());
            assertEquals(2L, records.get(1).getRecordNumber());
        }
    }

    @Test
    void testHeaderOnly() throws IOException {
        try (CSVParser parser = parse("id,name\n")) {
            final CSVProjection projection = CSVProjection.builder().column("id").column("name").compile(parser);
            final List<ProjectedRecord> records = parser.stream().map(projection::project).collect(Collectors.toList());
            assertTrue(records.isEmpty());
            assertEquals(2, projection.size());
        }
    }

    @Test
    void testCasePolicy() throws IOException {
        try (CSVParser parser = parse("ID,Name\n1,A\n")) {
            assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("id").compile(parser));
            final CSVProjection projection = CSVProjection.builder().column("id").column("NAME", "n")
                    .casePolicy(CasePolicy.INSENSITIVE).compile(parser);
            final ProjectedRecord record = projection.project(parser.iterator().next());
            assertEquals("1", record.get("id"));
            assertEquals("A", record.get("n"));
        }
    }

    @Test
    void testIllegalAlias() {
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("a").column("b", "a").compile("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("a", "").compile("a"));
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("a").column("a", 1).compile("a", "a"));
        assertThrows(NullPointerException.class, () -> CSVProjection.builder().column(null));
        assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("a", -1));
    }

    @Test
    void testStreamingConsumption() throws IOException {
        final StringBuilder csv = new StringBuilder("id,name\n");
        for (int i = 0; i < 100; i++) {
            csv.append(i).append(",v").append(i).append("\n");
        }
        try (CSVParser parser = parse(csv.toString())) {
            final CSVProjection projection = CSVProjection.builder().column("id").column("name").compile(parser);
            final AtomicInteger consumed = new AtomicInteger();
            final Iterator<CSVRecord> iterator = parser.iterator();
            final List<ProjectedRecord> first = new ArrayList<>();
            if (iterator.hasNext()) {
                consumed.incrementAndGet();
                first.add(projection.project(iterator.next()));
            }
            // Only one record was pulled from the parser; the projection itself holds no further input.
            assertEquals(1, consumed.get());
            assertEquals(1, first.size());
            assertEquals("0", first.get(0).get("id"));
            assertEquals("v0", first.get(0).get("name"));
            int rest = 0;
            while (iterator.hasNext()) {
                projection.project(iterator.next());
                rest++;
            }
            assertEquals(99, rest);
        }
    }

    @Test
    void testFailedCompilationDoesNotConsumeFirstRecord() throws IOException {
        try (CSVParser parser = parse("id,name\n1,A\n2,B\n")) {
            assertThrows(IllegalArgumentException.class, () -> CSVProjection.builder().column("missing").compile(parser));
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(2, records.size());
            assertEquals("1", records.get(0).get("id"));
        }
    }

    @Test
    void testPositionsAndRecordNumberPreserved() throws IOException {
        try (CSVParser parser = parse("id,name\n1,A\n2,B\n")) {
            final CSVProjection projection = CSVProjection.builder().column("id").column("name").compile(parser);
            for (final CSVRecord record : parser) {
                final ProjectedRecord projected = projection.project(record);
                assertEquals(record.getRecordNumber(), projected.getRecordNumber());
                assertEquals(record.getCharacterPosition(), projected.getCharacterPosition());
                assertEquals(record.getBytePosition(), projected.getBytePosition());
            }
        }
    }

    @Test
    void testValueRulesMatchCSVRecord() throws IOException {
        try (CSVParser parser = parse("id,name\n1,A\n")) {
            final CSVProjection projection = CSVProjection.builder().column("id").column("name").compile(parser);
            final CSVRecord record = parser.iterator().next();
            final ProjectedRecord projected = projection.project(record);
            assertEquals(record.get(0), projected.get("id"));
            assertEquals(record.get(1), projected.get("name"));
            assertEquals(record.get("name"), projected.get("name"));
            assertThrows(IllegalArgumentException.class, () -> projected.get("unknown"));
        }
    }
}
