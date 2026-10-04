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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Groups the records of a {@link CSVParser} into runs of <em>adjacent</em> records that share the same key.
 * <p>
 * The key of each record is computed by a caller-supplied function. A new group starts whenever the key of the next
 * record differs from the key of the current group; equal keys that are separated by a different key therefore form
 * distinct groups. For example, the key sequence {@code a, a, b, a} produces the three groups {@code (a, a)},
 * {@code (b)} and {@code (a)}; the trailing {@code a} is never merged back into the first group.
 * </p>
 * <p>
 * The adapter is streaming: it reads ahead at most one record (the first record of the following group). That
 * look-ahead record is never exposed through the currently open group; it is handed out only as the first record of
 * the next group. Records are always delivered in their original order, so {@link CSVRecord#getRecordNumber()} values
 * are strictly increasing and are never reshuffled.
 * </p>
 * <p>
 * If the caller advances to the next group before the current group is fully consumed, the remaining records of the
 * current group are discarded first, in original record order. Discarding is reading: the per-group record limit also
 * applies while discarding.
 * </p>
 * <p>
 * A per-group maximum record count may be configured. When a group grows beyond the limit, the read that crosses the
 * limit fails with a {@link CSVException} whose message carries the group key and the original record number of the
 * offending record.
 * </p>
 * <p>
 * Closing this adapter closes the underlying {@link CSVParser}.
 * </p>
 * <p>
 * Short example:
 * </p>
 * <pre>{@code
 * try (CSVParser parser = CSVParser.parse("key,value\na,1\na,2\nb,3\na,4\n",
 *         CSVFormat.DEFAULT.builder().setHeader().get());
 *         CSVRecordGroups<String> groups = CSVRecordGroups.groupAdjacent(parser, r -> r.get("key"))) {
 *     while (groups.hasNextGroup()) {
 *         CSVRecordGroups<String>.Group group = groups.nextGroup();
 *         System.out.println("key=" + group.getKey());
 *         while (group.hasNext()) {
 *             CSVRecord record = group.next();
 *             System.out.println("  #" + record.getRecordNumber() + " value=" + record.get("value"));
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param <K> The group key type.
 * @since 1.15.0
 */
public final class CSVRecordGroups<K> implements Closeable {

    /**
     * A stream of the records of a single group, valid only while it is the adapter's active group.
     * <p>
     * Records are consumed one at a time with {@link #hasNext()} and {@link #next()}. The first record of the
     * following group may already have been read ahead by the adapter, but it is never exposed through this group.
     * </p>
     */
    public final class Group {

        private final K key;
        private long recordCount;
        private boolean exhausted;

        private Group(final K key) {
            this.key = key;
        }

        /**
         * Gets the key shared by all records of this group.
         *
         * @return the group key, possibly {@code null} if the key function returns {@code null}.
         */
        public K getKey() {
            return key;
        }

        /**
         * Gets the number of records of this group consumed so far, including records discarded while advancing past
         * a partially consumed group.
         *
         * @return the number of records read from this group.
         */
        public long getRecordCount() {
            return recordCount;
        }

        /**
         * Tests whether this group has another record.
         *
         * @return {@code true} if another record belongs to this group.
         * @throws IOException          on a parse error or input read failure.
         * @throws CSVException         on invalid CSV input.
         * @throws IllegalStateException if this group is no longer the active group.
         */
        public boolean hasNext() throws IOException {
            checkActive();
            if (exhausted) {
                return false;
            }
            if (readAhead() == null || !Objects.equals(pendingKey, key)) {
                // End of stream, or the look-ahead record starts the next group: it stays
                // buffered and is not exposed through this group.
                exhausted = true;
                return false;
            }
            return true;
        }

        /**
         * Gets the next record of this group.
         *
         * @return the next record, in original record order.
         * @throws IOException            on a parse error or input read failure.
         * @throws CSVException           if the group exceeds the configured maximum record count; the message
         *                                carries the group key and the original record number.
         * @throws NoSuchElementException if this group has no more records.
         * @throws IllegalStateException  if this group is no longer the active group.
         */
        public CSVRecord next() throws IOException {
            if (!hasNext()) {
                throw new NoSuchElementException("No more records in group key=" + key);
            }
            final CSVRecord record = pending;
            pending = null;
            pendingKey = null;
            recordCount++;
            if (recordCount > maxGroupSize) {
                throw new CSVException("Group exceeds maximum of %d records: key=%s, recordNumber=%d", maxGroupSize,
                        key, record.getRecordNumber());
            }
            return record;
        }

        private void checkActive() {
            if (this != active) {
                throw new IllegalStateException("Group key=" + key + " is no longer the active group");
            }
        }
    }

    /**
     * Creates an adapter that groups adjacent records of the given parser by the given key function, without a
     * per-group record limit.
     *
     * @param <K>         The group key type.
     * @param parser      The underlying parser, closed when the adapter is closed.
     * @param keyFunction Computes the group key of a record.
     * @return a new grouping adapter.
     */
    public static <K> CSVRecordGroups<K> groupAdjacent(final CSVParser parser,
            final Function<? super CSVRecord, ? extends K> keyFunction) {
        return new CSVRecordGroups<>(parser, keyFunction, Long.MAX_VALUE);
    }

    /**
     * Creates an adapter that groups adjacent records of the given parser by the given key function, limiting the
     * number of records per group.
     *
     * @param <K>          The group key type.
     * @param parser       The underlying parser, closed when the adapter is closed.
     * @param keyFunction  Computes the group key of a record.
     * @param maxGroupSize The maximum number of records allowed per group, must be at least 1. The read that crosses
     *                     the limit fails with a {@link CSVException} carrying the group key and record number.
     * @return a new grouping adapter.
     */
    public static <K> CSVRecordGroups<K> groupAdjacent(final CSVParser parser,
            final Function<? super CSVRecord, ? extends K> keyFunction, final long maxGroupSize) {
        return new CSVRecordGroups<>(parser, keyFunction, maxGroupSize);
    }

    private final CSVParser parser;
    private final Function<? super CSVRecord, ? extends K> keyFunction;
    private final long maxGroupSize;

    /** The look-ahead record: the first record of the next group, not yet exposed to any group. */
    private CSVRecord pending;

    /** The key of the look-ahead record. */
    private K pendingKey;

    /** The group currently being consumed, or {@code null}. */
    private Group active;

    private boolean closed;

    private CSVRecordGroups(final CSVParser parser, final Function<? super CSVRecord, ? extends K> keyFunction,
            final long maxGroupSize) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.keyFunction = Objects.requireNonNull(keyFunction, "keyFunction");
        if (maxGroupSize < 1) {
            throw new IllegalArgumentException("maxGroupSize must be at least 1: " + maxGroupSize);
        }
        this.maxGroupSize = maxGroupSize;
    }

    /**
     * Closes this adapter and the underlying {@link CSVParser}. Closing more than once has no effect.
     *
     * @throws IOException If an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            active = null;
            pending = null;
            pendingKey = null;
            parser.close();
        }
    }

    /**
     * Tests whether another group is available, discarding first any unread records of a partially consumed current
     * group.
     *
     * @return {@code true} if another group is available.
     * @throws IOException  on a parse error or input read failure.
     * @throws CSVException if discarding the rest of the current group crosses the maximum group size.
     */
    public boolean hasNextGroup() throws IOException {
        if (closed) {
            return false;
        }
        discardActiveGroup();
        return readAhead() != null;
    }

    /**
     * Tests whether this adapter has been closed.
     *
     * @return {@code true} if this adapter has been closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Advances to the next group, discarding first any unread records of a partially consumed current group.
     *
     * @return the next group, ready for record-by-record consumption.
     * @throws IOException            on a parse error or input read failure.
     * @throws CSVException           if discarding the rest of the current group crosses the maximum group size.
     * @throws NoSuchElementException if no more groups are available.
     */
    public Group nextGroup() throws IOException {
        if (!hasNextGroup()) {
            throw new NoSuchElementException("No more record groups available");
        }
        active = new Group(pendingKey);
        return active;
    }

    /**
     * Discards the remaining records of the active group, in original record order. The per-group record limit
     * applies while discarding.
     */
    private void discardActiveGroup() throws IOException {
        if (active != null) {
            while (active.hasNext()) {
                active.next();
            }
            active = null;
        }
    }

    /**
     * Gets the look-ahead record, reading it from the parser if needed. At most one record is ever read ahead.
     *
     * @return the buffered first record of the next group, or {@code null} at the end of the stream.
     */
    private CSVRecord readAhead() throws IOException {
        if (pending == null) {
            pending = parser.nextRecord();
            if (pending != null) {
                pendingKey = keyFunction.apply(pending);
            }
        }
        return pending;
    }
}
