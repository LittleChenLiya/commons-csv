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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVRecordGrouper}.
 */
class CSVRecordGrouperTest {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("key", "value")
            .setSkipHeaderRecord(true)
            .build();

    private static CSVParser parser(final String csv) throws IOException {
        return CSVParser.parse(csv, FORMAT);
    }

    /**
     * A reader fixture that counts how many times {@link Closeable#close()} is invoked.
     */
    private static final class CloseCountingReader extends FilterReader {

        private final AtomicInteger closeCount = new AtomicInteger();

        CloseCountingReader(final Reader in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }

        int getCloseCount() {
            return closeCount.get();
        }
    }

    /**
     * Collects the groups into a list of key to record-number list pairs.
     */
    private static List<String[]> collectGroups(final CSVRecordGrouper<String> grouper) {
        final List<String[]> result = new ArrayList<>();
        for (final CSVRecordGrouper.Group<String> group : grouper) {
            final List<String> numbers = new ArrayList<>();
            for (final CSVRecord record : group) {
                numbers.add(Long.toString(record.getRecordNumber()));
            }
            result.add(new String[] { group.getKey(), String.join(",", numbers) });
        }
        return result;
    }

    @Test
    void groupsAdjacentRecordsByKey() throws Exception {
        final String csv = "key,value\n" + "a,1\n" + "a,2\n" + "b,3\n" + "a,4\n";
        try (CSVParser parser = parser(csv); CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"))) {
            final List<String[]> groups = collectGroups(grouper);
            assertEquals(3, groups.size());
            assertEquals(Arrays.asList("a", "1,2"), Arrays.asList(groups.get(0)));
            assertEquals(Arrays.asList("b", "3"), Arrays.asList(groups.get(1)));
            assertEquals(Arrays.asList("a", "4"), Arrays.asList(groups.get(2)));
        }
    }

    @Test
    void firstGroupReadOneRecordThenAdvanceLandsOnB() throws Exception {
        final String csv = "key,value\n" + "a,1\n" + "a,2\n" + "b,3\n" + "a,4\n";
        try (CSVParser parser = parser(csv); CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"))) {
            final Iterator<CSVRecordGrouper.Group<String>> groupIterator = grouper.iterator();
            // Read only the first record of the first group, then advance without finishing it.
            final CSVRecordGrouper.Group<String> first = groupIterator.next();
            assertEquals("a", first.getKey());
            final Iterator<CSVRecord> recordIterator = first.iterator();
            final CSVRecord one = recordIterator.next();
            assertEquals(1, one.getRecordNumber());
            // The group still has records, but we advance without finishing it.
            assertTrue(recordIterator.hasNext());
            // Advancing must discard the remainder of group a and land on group b with its original record number.
            final CSVRecordGrouper.Group<String> second = groupIterator.next();
            assertEquals("b", second.getKey());
            final CSVRecord three = second.iterator().next();
            assertEquals(3, three.getRecordNumber());
        }
    }

    @Test
    void lookaheadRecordNotExposedToCurrentGroup() throws Exception {
        final String csv = "key,value\n" + "a,1\n" + "a,2\n" + "b,3\n";
        try (CSVParser parser = parser(csv); CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"))) {
            final Iterator<CSVRecordGrouper.Group<String>> groupIterator = grouper.iterator();
            final CSVRecordGrouper.Group<String> groupA = groupIterator.next();
            final List<Long> numbers = new ArrayList<>();
            for (final CSVRecord record : groupA) {
                numbers.add(record.getRecordNumber());
            }
            // The first group must contain only the two a records; the prefetched b record must not leak in.
            assertEquals(Arrays.asList(1L, 2L), numbers);
            final CSVRecordGrouper.Group<String> groupB = groupIterator.next();
            assertEquals("b", groupB.getKey());
            final CSVRecord three = groupB.iterator().next();
            assertEquals(3, three.getRecordNumber());
        }
    }

    @Test
    void maxRecordsPerGroupThrowsWithKeyAndRecordNumber() throws Exception {
        final String csv = "key,value\n" + "a,1\n" + "a,2\n" + "a,3\n";
        try (CSVParser parser = parser(csv); CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"), 2)) {
            final CSVRecordGrouper.Group<String> group = grouper.iterator().next();
            final Iterator<CSVRecord> recordIterator = group.iterator();
            assertEquals(1, recordIterator.next().getRecordNumber());
            assertEquals(2, recordIterator.next().getRecordNumber());
            final UncheckedIOException thrown = assertThrows(UncheckedIOException.class, recordIterator::next);
            final Throwable cause = thrown.getCause();
            assertInstanceOf(CSVException.class, cause);
            final String message = cause.getMessage();
            assertTrue(message.contains("a"), "message should contain group key: " + message);
            assertTrue(message.contains("3"), "message should contain record number 3: " + message);
        }
    }

    @Test
    void closeClosesUnderlyingParser() throws Exception {
        final String csv = "key,value\n" + "a,1\n" + "b,2\n";
        final CloseCountingReader reader = new CloseCountingReader(new StringReader(csv));
        try (CSVParser parser = CSVParser.parse(reader, FORMAT)) {
            try (CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"))) {
                collectGroups(grouper);
            }
            assertEquals(1, reader.getCloseCount(), "closing the grouper should close the underlying reader once");
        }
    }

    @Test
    void emptyInputProducesNoGroups() throws Exception {
        final String csv = "key,value\n";
        try (CSVParser parser = parser(csv); CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"))) {
            assertFalse(grouper.iterator().hasNext());
        }
    }

    @Test
    void invalidMaxRecordsPerGroupThrows() {
        final String csv = "key,value\n" + "a,1\n";
        assertThrows(IllegalArgumentException.class, () -> {
            try (CSVParser parser = parser(csv)) {
                CSVRecordGrouper.of(parser, record -> record.get("key"), 0);
            }
        });
    }

    @Test
    void shortExample() throws Exception {
        final String csv = "key,value\n" + "a,1\n" + "a,2\n" + "b,3\n" + "a,4\n";
        try (CSVParser parser = parser(csv); CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"), 2)) {
            final List<String> lines = new ArrayList<>();
            for (final CSVRecordGrouper.Group<String> group : grouper) {
                lines.add(group.getKey());
                for (final CSVRecord record : group) {
                    lines.add(record.getRecordNumber() + ": " + record.get("value"));
                }
            }
            assertEquals(Arrays.asList("a", "1: 1", "2: 2", "b", "3: 3", "a", "4: 4"), lines);
        }
    }
}
