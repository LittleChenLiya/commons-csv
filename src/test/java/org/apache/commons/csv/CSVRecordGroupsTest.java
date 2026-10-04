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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVRecordGroups}.
 */
class CSVRecordGroupsTest {

    /**
     * Close-counting fixture: a reader that records how many times it has been closed.
     */
    private static final class CloseCountingReader extends StringReader {

        private int closeCount;

        CloseCountingReader(final String input) {
            super(input);
        }

        @Override
        public void close() {
            closeCount++;
            super.close();
        }
    }

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setHeader().get();

    private static CSVRecordGroups<String> groups(final CSVParser parser) {
        return CSVRecordGroups.groupAdjacent(parser, record -> record.get("key"));
    }

    private static List<Long> recordNumbers(final CSVRecordGroups<String>.Group group) throws IOException {
        final List<Long> numbers = new ArrayList<>();
        while (group.hasNext()) {
            numbers.add(group.next().getRecordNumber());
        }
        return numbers;
    }

    @Test
    void testAdjacentGroupsKeepOriginalRecordNumbers() throws IOException {
        // a,a,b,a must produce three groups: the trailing "a" must not merge into the first group.
        try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\nb,3\na,4\n", FORMAT);
                CSVRecordGroups<String> groups = groups(parser)) {
            assertTrue(groups.hasNextGroup());
            final CSVRecordGroups<String>.Group first = groups.nextGroup();
            assertEquals("a", first.getKey());
            assertEquals(Arrays.asList(1L, 2L), recordNumbers(first));

            assertTrue(groups.hasNextGroup());
            final CSVRecordGroups<String>.Group second = groups.nextGroup();
            assertEquals("b", second.getKey());
            assertEquals(Arrays.asList(3L), recordNumbers(second));

            assertTrue(groups.hasNextGroup());
            final CSVRecordGroups<String>.Group third = groups.nextGroup();
            assertEquals("a", third.getKey());
            assertEquals(Arrays.asList(4L), recordNumbers(third));

            assertFalse(groups.hasNextGroup());
            assertThrows(NoSuchElementException.class, groups::nextGroup);
        }
    }

    @Test
    void testCloseClosesUnderlyingParserExactlyOnce() throws IOException {
        final CloseCountingReader reader = new CloseCountingReader("key,value\na,1\n");
        final CSVParser parser = CSVParser.builder().setReader(reader).setFormat(FORMAT).get();
        final CSVRecordGroups<String> groups = groups(parser);
        assertTrue(groups.hasNextGroup());
        groups.close();
        assertTrue(groups.isClosed());
        assertTrue(parser.isClosed());
        assertEquals(1, reader.closeCount);
        groups.close();
        assertEquals(1, reader.closeCount, "closing twice must not close the parser twice");
        assertFalse(groups.hasNextGroup());
    }

    @Test
    void testLookAheadRecordIsNotExposedToCurrentGroup() throws IOException {
        try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\nb,3\n", FORMAT);
                CSVRecordGroups<String> groups = groups(parser)) {
            final CSVRecordGroups<String>.Group first = groups.nextGroup();
            assertEquals("a", first.getKey());
            assertEquals(1L, first.next().getRecordNumber());
            assertEquals(2L, first.next().getRecordNumber());
            // The pre-read "b" record belongs to the next group and must not leak into this one.
            assertFalse(first.hasNext());
            assertThrows(NoSuchElementException.class, first::next);

            final CSVRecordGroups<String>.Group second = groups.nextGroup();
            assertEquals("b", second.getKey());
            // The look-ahead record keeps its original record number: no reordering.
            assertEquals(3L, second.next().getRecordNumber());
            assertFalse(second.hasNext());
            assertFalse(groups.hasNextGroup());
        }
    }

    @Test
    void testMaxGroupSizeExceededReportsKeyAndRecordNumber() throws IOException {
        try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\na,3\n", FORMAT);
                CSVRecordGroups<String> groups = CSVRecordGroups.groupAdjacent(parser, record -> record.get("key"),
                        2)) {
            final CSVRecordGroups<String>.Group group = groups.nextGroup();
            assertEquals("a", group.getKey());
            assertEquals(1L, group.next().getRecordNumber());
            assertEquals(2L, group.next().getRecordNumber());
            final CSVException e = assertThrows(CSVException.class, group::next);
            assertTrue(e.getMessage().contains("key=a"), () -> "message must carry the group key: " + e.getMessage());
            assertTrue(e.getMessage().contains("recordNumber=3"),
                    () -> "message must carry the original record number: " + e.getMessage());
        }
    }

    @Test
    void testMaxGroupSizeExceededWhileDiscardingOnAdvance() throws IOException {
        try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\na,3\nb,4\n", FORMAT);
                CSVRecordGroups<String> groups = CSVRecordGroups.groupAdjacent(parser, record -> record.get("key"),
                        2)) {
            final CSVRecordGroups<String>.Group group = groups.nextGroup();
            assertEquals(1L, group.next().getRecordNumber());
            // Advancing discards the rest of the group; the discard that crosses the limit fails.
            final CSVException e = assertThrows(CSVException.class, groups::hasNextGroup);
            assertTrue(e.getMessage().contains("key=a"), () -> "message must carry the group key: " + e.getMessage());
            assertTrue(e.getMessage().contains("recordNumber=3"),
                    () -> "message must carry the original record number: " + e.getMessage());
        }
    }

    @Test
    void testPartialConsumptionAdvancesPastDiscardedRecords() throws IOException {
        try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\nb,3\na,4\n", FORMAT);
                CSVRecordGroups<String> groups = groups(parser)) {
            final CSVRecordGroups<String>.Group first = groups.nextGroup();
            assertEquals("a", first.getKey());
            // Read only one record of the first group, then advance: record a,2 is discarded.
            assertEquals(1L, first.next().getRecordNumber());

            assertTrue(groups.hasNextGroup());
            final CSVRecordGroups<String>.Group second = groups.nextGroup();
            assertEquals("b", second.getKey(), "advancing must land on the b group");
            final CSVRecord record = second.next();
            assertEquals(3L, record.getRecordNumber());
            assertEquals("3", record.get("value"));
            assertFalse(second.hasNext());

            assertTrue(groups.hasNextGroup());
            final CSVRecordGroups<String>.Group third = groups.nextGroup();
            assertEquals("a", third.getKey());
            assertEquals(4L, third.next().getRecordNumber());
            assertFalse(groups.hasNextGroup());
        }
    }

    @Test
    void testShortExample() throws IOException {
        final StringBuilder out = new StringBuilder();
        try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\nb,3\na,4\n", FORMAT);
                CSVRecordGroups<String> groups = groups(parser)) {
            while (groups.hasNextGroup()) {
                final CSVRecordGroups<String>.Group group = groups.nextGroup();
                out.append("key=").append(group.getKey()).append('\n');
                while (group.hasNext()) {
                    final CSVRecord record = group.next();
                    out.append("  #").append(record.getRecordNumber()).append(" value=").append(record.get("value"))
                            .append('\n');
                }
            }
        }
        assertEquals("key=a\n  #1 value=1\n  #2 value=2\nkey=b\n  #3 value=3\nkey=a\n  #4 value=4\n", out.toString());
    }

    @Test
    void testStaleGroupIsRejected() throws IOException {
        try (CSVParser parser = CSVParser.parse("key,value\na,1\nb,2\n", FORMAT);
                CSVRecordGroups<String> groups = groups(parser)) {
            final CSVRecordGroups<String>.Group first = groups.nextGroup();
            groups.nextGroup();
            assertThrows(IllegalStateException.class, first::hasNext);
            assertThrows(IllegalStateException.class, first::next);
        }
    }
}
