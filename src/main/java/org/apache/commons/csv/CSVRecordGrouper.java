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

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Groups the records of a {@link CSVParser} into adjacent groups.
 *
 * <p>
 * A group is a run of consecutive records that share the same key. The key is computed for each record by a
 * caller-supplied function. Adjacency matters: two records with the same key only belong to the same group when every
 * record between them also has that key. For example, the input {@code a, a, b, a} produces three groups,
 * {@code [a, a]}, {@code [b]} and {@code [a]}; the trailing {@code a} is <strong>not</strong> merged into the first
 * group.
 * </p>
 *
 * <p>
 * The grouper reads the underlying parser with a one-record lookahead: it prefetches only the first record of the
 * next group. That prefetched record is never exposed to the current group; it is held back until the next group is
 * requested. Each group can be consumed record by record through its {@link Group#iterator()}. If the caller moves on
 * to the next group before the current group has been fully consumed, the remaining records of the current group are
 * discarded first, so no record is ever lost or reordered. The original {@link CSVRecord#getRecordNumber() record
 * numbers} are preserved.
 * </p>
 *
 * <p>
 * An optional limit on the number of records per group can be set. When a group exceeds the limit, reading the record
 * that crosses the limit throws an {@link UncheckedIOException} wrapping a {@link CSVException} that carries the group
 * key and the original record number.
 * </p>
 *
 * <p>
 * Closing the grouper closes the underlying parser.
 * </p>
 *
 * <pre>{@code
 * try (CSVParser parser = CSVParser.parse(reader, format);
 *         CSVRecordGrouper<String> grouper = CSVRecordGrouper.of(parser, record -> record.get("key"), 2)) {
 *     for (CSVRecordGrouper.Group<String> group : grouper) {
 *         System.out.println(group.getKey());
 *         for (CSVRecord record : group) {
 *             System.out.println(record.getRecordNumber() + ": " + record.get("value"));
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param <K> the type of the group key.
 * @since 1.15.0
 */
public final class CSVRecordGrouper<K> implements Iterable<CSVRecordGrouper.Group<K>>, Closeable {

    /**
     * A group of adjacent {@link CSVRecord CSVRecords} that share the same key.
     *
     * @param <K> the type of the group key.
     */
    public interface Group<K> extends Iterable<CSVRecord> {

        /**
         * Gets the key shared by all records in this group.
         *
         * @return the group key, may be {@code null} if the key function returned {@code null}.
         */
        K getKey();

        /**
         * Returns an iterator over the records of this group.
         * <p>
         * The iterator yields only records that belong to this group; the one-record lookahead that starts the next
         * group is never exposed through this iterator.
         * </p>
         *
         * @return an iterator over the records of this group.
         */
        @Override
        Iterator<CSVRecord> iterator();
    }

    /**
     * The iterator over the records of the underlying parser.
     */
    private final Iterator<CSVRecord> recordIterator;

    /**
     * The function that computes the group key of a record.
     */
    private final Function<? super CSVRecord, ? extends K> keyExtractor;

    /**
     * The maximum number of records allowed per group, or {@link Long#MAX_VALUE} for no limit.
     */
    private final long maxRecordsPerGroup;

    /**
     * The one-record lookahead: the first record of the next group, or {@code null} at end of stream.
     */
    private CSVRecord lookahead;

    /**
     * The iterator of the group currently being consumed, used to discard unconsumed records when the caller moves on.
     */
    private GroupIterator currentGroup;

    /**
     * The single group iterator returned by {@link #iterator()}.
     */
    private final Iterator<Group<K>> groupIterator;

    /**
     * The underlying parser, closed by {@link #close()}.
     */
    private final CSVParser parser;

    /**
     * Constructs a new instance.
     *
     * @param parser             the parser to read from, must not be {@code null}.
     * @param keyExtractor       the function that computes the group key of a record, must not be {@code null}.
     * @param maxRecordsPerGroup the maximum number of records allowed per group, must be positive.
     * @throws IOException if an I/O error occurs while prefetching the first record.
     */
    private CSVRecordGrouper(final CSVParser parser, final Function<? super CSVRecord, ? extends K> keyExtractor,
            final long maxRecordsPerGroup) throws IOException {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor");
        if (maxRecordsPerGroup <= 0) {
            throw new IllegalArgumentException("maxRecordsPerGroup must be positive: " + maxRecordsPerGroup);
        }
        this.maxRecordsPerGroup = maxRecordsPerGroup;
        this.recordIterator = parser.iterator();
        this.lookahead = recordIterator.hasNext() ? recordIterator.next() : null;
        this.groupIterator = new Iterator<Group<K>>() {

            @Override
            public boolean hasNext() {
                return lookahead != null;
            }

            @Override
            public Group<K> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more CSV record groups");
                }
                // If the previous group was not fully consumed, discard its remaining records first. The lookahead
                // then already holds the first record of the next group.
                if (currentGroup != null) {
                    while (currentGroup.hasNext()) {
                        currentGroup.next();
                    }
                }
                final K key = keyExtractor.apply(lookahead);
                final GroupIterator group = new GroupIterator(key);
                currentGroup = group;
                return group;
            }
        };
    }

    /**
     * Creates a grouper with no limit on the number of records per group.
     *
     * @param <K>          the type of the group key.
     * @param parser       the parser to read from, must not be {@code null}.
     * @param keyExtractor the function that computes the group key of a record, must not be {@code null}.
     * @return a new grouper.
     * @throws UncheckedIOException if an I/O error occurs while prefetching the first record.
     */
    public static <K> CSVRecordGrouper<K> of(final CSVParser parser, final Function<? super CSVRecord, ? extends K> keyExtractor) {
        try {
            return new CSVRecordGrouper<>(parser, keyExtractor, Long.MAX_VALUE);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a grouper with a limit on the number of records per group.
     * <p>
     * When a group exceeds the given limit, reading the record that crosses the limit throws an
     * {@link UncheckedIOException} wrapping a {@link CSVException} that carries the group key and the original record
     * number.
     * </p>
     *
     * @param <K>                the type of the group key.
     * @param parser             the parser to read from, must not be {@code null}.
     * @param keyExtractor       the function that computes the group key of a record, must not be {@code null}.
     * @param maxRecordsPerGroup the maximum number of records allowed per group, must be positive.
     * @return a new grouper.
     * @throws IllegalArgumentException if {@code maxRecordsPerGroup <= 0}.
     * @throws UncheckedIOException     if an I/O error occurs while prefetching the first record.
     */
    public static <K> CSVRecordGrouper<K> of(final CSVParser parser, final Function<? super CSVRecord, ? extends K> keyExtractor,
            final long maxRecordsPerGroup) {
        try {
            return new CSVRecordGrouper<>(parser, keyExtractor, maxRecordsPerGroup);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Gets an iterator over the adjacent groups.
     * <p>
     * The returned iterator consumes the underlying parser; obtaining more than one iterator has undefined behavior.
     * </p>
     *
     * @return an iterator over the groups.
     */
    @Override
    public Iterator<Group<K>> iterator() {
        return groupIterator;
    }

    /**
     * Closes the underlying parser.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        parser.close();
    }

    /**
     * The group iterator returned by {@link #iterator()}.
     */
    private final class GroupIterator implements Iterator<CSVRecord>, Group<K> {

        private final K key;

        private long recordCount;

        GroupIterator(final K key) {
            this.key = key;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public Iterator<CSVRecord> iterator() {
            return this;
        }

        @Override
        public boolean hasNext() {
            return lookahead != null && Objects.equals(keyExtractor.apply(lookahead), key);
        }

        @Override
        public CSVRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more records in group " + key);
            }
            final CSVRecord record = lookahead;
            lookahead = null;
            recordCount++;
            if (recordCount > maxRecordsPerGroup) {
                throw new UncheckedIOException(new CSVException(
                        "Group %s exceeds the maximum of %,d records per group at record number %,d",
                        Objects.toString(key, "null"), Long.valueOf(maxRecordsPerGroup), Long.valueOf(record.getRecordNumber())));
            }
            lookahead = recordIterator.hasNext() ? recordIterator.next() : null;
            return record;
        }
    }
}
