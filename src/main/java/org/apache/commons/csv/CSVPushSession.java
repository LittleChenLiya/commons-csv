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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses CSV input that arrives in chunks, push style.
 *
 * <p>
 * A session is created for a fixed {@link CSVFormat}; the delimiter, quote, escape, comment and empty-line settings of that format are honored exactly as
 * {@link CSVParser} honors them when reading from a {@link Reader}. Input is supplied through {@link #feed(CharSequence)}, which returns the records that
 * became complete within the fed chunk, and {@link #finish()}, which signals the real end of input. A record that is still incomplete at the end of a chunk
 * is retained internally and is never delivered before it is complete; {@code feed} never commits a trailing record, only {@code finish} (or a line
 * terminator) can complete the last record.
 * </p>
 *
 * <h2>Counting conventions</h2>
 * <p>
 * All counters match the equivalent {@link CSVParser} counters:
 * </p>
 * <ul>
 * <li>Record numbers are 1-based: the first record delivered has record number 1, see {@link CSVRecord#getRecordNumber()}.</li>
 * <li>{@link #getRecordNumber()} returns the number of records delivered so far, so it is 0 before any record is delivered.</li>
 * <li>Line numbers are 1-based once any input has been consumed, see {@link #getCurrentLineNumber()}.</li>
 * <li>Character positions are 0-based counts of consumed UTF-16 code units, see {@link #getCharacterPosition()} and
 * {@link CSVRecord#getCharacterPosition()}.</li>
 * </ul>
 *
 * <h2>End of input</h2>
 * <ul>
 * <li>Finishing an empty session (no input at all) yields no records and is not an error.</li>
 * <li>A final record without a trailing line terminator is committed by {@code finish()}.</li>
 * <li>Calling {@code finish()} again after a successful finish is a no-op that returns an empty list.</li>
 * <li>Finishing with an unclosed quoted field throws a {@link CSVException}, unless the format is lenient
 * ({@link CSVFormat.Builder#setLenientEof(boolean)}).</li>
 * </ul>
 *
 * <h2>Record size limit</h2>
 * <p>
 * {@link Builder#setMaxRecordChars(long)} sets a limit on the number of raw UTF-16 code units a single record may span in the input, including its line
 * terminator. When a record exceeds the limit it is not delivered, the session enters the failed state and the current call throws a {@link CSVException}.
 * Records already delivered stay valid. Once failed, every subsequent {@code feed} or {@code finish} call is rejected with an {@link IllegalStateException}.
 * </p>
 *
 * <p>
 * Instances are not thread-safe.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVPushSession {

    /**
     * Builds {@link CSVPushSession} instances.
     */
    public static final class Builder {

        private CSVFormat format = CSVFormat.DEFAULT;
        private long maxRecordChars = -1;

        private Builder() {
            // empty
        }

        /**
         * Creates a new session.
         *
         * @return a new session.
         */
        public CSVPushSession get() {
            return new CSVPushSession(this);
        }

        /**
         * Sets the CSV format, defaults to {@link CSVFormat#DEFAULT}.
         *
         * @param format the CSV format.
         * @return {@code this} instance.
         */
        public Builder setFormat(final CSVFormat format) {
            this.format = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets the maximum number of raw UTF-16 code units a single record may span in the input, including its line terminator. The default, -1, disables
         * the limit.
         *
         * @param maxRecordChars the limit, must be &gt;= 0, or -1 to disable.
         * @return {@code this} instance.
         */
        public Builder setMaxRecordChars(final long maxRecordChars) {
            if (maxRecordChars < -1) {
                throw new IllegalArgumentException("maxRecordChars must be >= 0 or -1 to disable the limit");
            }
            this.maxRecordChars = maxRecordChars;
            return this;
        }
    }

    /**
     * Signals that the pushed input is exhausted but the session has not been finished, so more input may arrive later.
     */
    static final class NeedMoreDataException extends IOException {

        private static final long serialVersionUID = 1L;

        NeedMoreDataException() {
            super("More input data is required to complete the current record.");
        }
    }

    /**
     * A {@link Reader} over the fed input. Returns {@code -1} only once the session is finished; while more input may still arrive, reading past the fed
     * prefix throws {@link NeedMoreDataException} instead of reporting end of stream.
     */
    static final class PushReader extends Reader {

        private final StringBuilder buffer = new StringBuilder();
        private int position;
        private boolean finished;

        void append(final CharSequence data) {
            buffer.append(data);
        }

        @Override
        public void close() {
            finished = true;
        }

        void compact() {
            if (position > 0) {
                buffer.delete(0, position);
                position = 0;
            }
        }

        void finish() {
            finished = true;
        }

        int getPosition() {
            return position;
        }

        @Override
        public int read() throws IOException {
            if (position < buffer.length()) {
                return buffer.charAt(position++);
            }
            if (finished) {
                return -1;
            }
            throw new NeedMoreDataException();
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            final int available = buffer.length() - position;
            if (available <= 0) {
                if (finished) {
                    return -1;
                }
                throw new NeedMoreDataException();
            }
            final int count = Math.min(len, available);
            buffer.getChars(position, position + count, cbuf, off);
            position += count;
            return count;
        }

        @Override
        public boolean ready() {
            return position < buffer.length();
        }

        void rewind(final int newPosition) {
            position = newPosition;
        }
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final ExtendedBufferedReader reader;
    private final PushReader pushReader;
    private final CSVParser parser;
    private final long maxRecordChars;
    private int pushCheckpoint;
    private boolean failed;
    private boolean finished;

    /**
     * Creates a new session for the given format with no record size limit.
     *
     * @param format the CSV format.
     */
    public CSVPushSession(final CSVFormat format) {
        this(builder().setFormat(format));
    }

    private CSVPushSession(final Builder builder) {
        final CSVFormat format = builder.format;
        if (format.useMaxRows()) {
            throw new IllegalArgumentException(
                    "CSVFormat.Builder.setMaxRows(long) is not supported by CSVPushSession; limit the number of records in the caller instead.");
        }
        this.pushReader = new PushReader();
        // A buffer size of 1 keeps every character the reader lookahead consumed accounted for by the peek pushback buffer, so that
        // checkpoint()/rewind() plus the PushReader position fully describe the reader state.
        this.reader = new ExtendedBufferedReader(pushReader, 1);
        this.parser = new CSVParser(format, reader);
        this.maxRecordChars = builder.maxRecordChars;
    }

    private void checkpoint() {
        reader.checkpoint();
        pushCheckpoint = pushReader.getPosition();
    }

    private void rewind() {
        reader.rewind();
        pushReader.rewind(pushCheckpoint);
    }

    private void checkActive(final String operation) {
        if (failed) {
            throw new IllegalStateException("This CSVPushSession is in a failed state; " + operation + " is not allowed.");
        }
        if (finished) {
            throw new IllegalStateException("This CSVPushSession is finished; " + operation + " is not allowed.");
        }
    }

    private void checkRecordSize(final CSVRecord record) throws CSVException {
        if (maxRecordChars >= 0) {
            final long size = reader.getPosition() - record.getCharacterPosition();
            if (size > maxRecordChars) {
                throw new CSVException("Record %,d spans %,d characters which exceeds the configured maximum of %,d", Long.valueOf(record.getRecordNumber()),
                        Long.valueOf(size), Long.valueOf(maxRecordChars));
            }
        }
    }

    /**
     * Parses as far as the fed input allows, checkpointing at every record boundary so that only the incomplete trailing record is re-parsed when more
     * input arrives.
     *
     * @return the records completed by this call.
     * @throws CSVException on invalid CSV input or when a record exceeds the configured size limit.
     */
    private List<CSVRecord> drain() throws CSVException {
        final List<CSVRecord> records = new ArrayList<>();
        try {
            checkpoint();
            try {
                parser.ensureHeaders();
            } catch (final NeedMoreDataException e) {
                rewind();
                return records;
            }
            while (true) {
                checkpoint();
                final CSVRecord record;
                try {
                    record = parser.nextRecord();
                } catch (final NeedMoreDataException e) {
                    // The trailing record is incomplete: roll back to its start and keep it for the next feed.
                    rewind();
                    break;
                }
                if (record == null) {
                    break;
                }
                checkRecordSize(record);
                records.add(record);
            }
            pushReader.compact();
            return records;
        } catch (final CSVException e) {
            failed = true;
            throw e;
        } catch (final IOException e) {
            failed = true;
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Feeds a chunk of input and returns the records that became complete within it. The chunk may split the input anywhere: between a CR and an LF, inside
     * an escaped or doubled quote, at a line break inside a quoted field, or between empty fields. A record that is still incomplete at the end of the chunk
     * is retained, not delivered, and completed by later input.
     *
     * @param data the input chunk, not null.
     * @return the records completed by this chunk, possibly empty, never null.
     * @throws CSVException on invalid CSV input or when a record exceeds the configured size limit.
     * @throws IllegalStateException if the session is finished or failed.
     */
    public List<CSVRecord> feed(final CharSequence data) throws CSVException {
        checkActive("feed");
        pushReader.append(Objects.requireNonNull(data, "data"));
        return drain();
    }

    /**
     * Signals the real end of input and returns the remaining records, committing a final record that has no trailing line terminator. Fails with a
     * {@link CSVException} on an unclosed quoted field unless the format is lenient. Calling {@code finish()} again is a no-op that returns an empty list.
     *
     * @return the remaining records, possibly empty, never null.
     * @throws CSVException on invalid CSV input or when a record exceeds the configured size limit.
     * @throws IllegalStateException if the session is failed.
     */
    public List<CSVRecord> finish() throws CSVException {
        if (failed) {
            throw new IllegalStateException("This CSVPushSession is in a failed state; finish is not allowed.");
        }
        if (finished) {
            return Collections.emptyList();
        }
        finished = true;
        pushReader.finish();
        return drain();
    }

    /**
     * Gets the current character position, a 0-based count of consumed UTF-16 code units.
     *
     * @return the current character position.
     */
    public long getCharacterPosition() {
        return reader.getPosition();
    }

    /**
     * Gets the current 1-based line number in the input. If the input has multi-line values, the returned number does not correspond to the record number.
     *
     * @return the current line number.
     */
    public long getCurrentLineNumber() {
        return parser.getCurrentLineNumber();
    }

    /**
     * Gets the first end-of-line string encountered, or null if none was seen yet.
     *
     * @return the first end-of-line string.
     */
    public String getFirstEndOfLine() {
        return parser.getFirstEndOfLine();
    }

    /**
     * Gets the header comment, if any. The header comment appears before the header record.
     *
     * @return the header comment, or null if no comment is available.
     */
    public String getHeaderComment() {
        return parser.isHeadersInitialized() ? parser.getHeaderComment() : null;
    }

    /**
     * Gets a copy of the header map as defined by the format's header configuration, or null if no headers are defined or not enough input has been fed to
     * read them.
     *
     * @return a copy of the header map, or null.
     */
    public Map<String, Integer> getHeaderMap() {
        return parser.isHeadersInitialized() ? parser.getHeaderMap() : null;
    }

    /**
     * Gets a read-only list of header names in column order, or an empty list if no headers are defined or not enough input has been fed to read them.
     *
     * @return a read-only list of header names.
     */
    public List<String> getHeaderNames() {
        return parser.isHeadersInitialized() ? parser.getHeaderNames() : Collections.emptyList();
    }

    /**
     * Gets the number of records delivered so far. The first record delivered has record number 1, so this is 0 before any record is delivered.
     *
     * @return the number of records delivered so far.
     */
    public long getRecordNumber() {
        return parser.getRecordNumber();
    }

    /**
     * Gets the trailer comment, if any. Trailer comments are located between the last record and the end of the input and are only available after
     * {@link #finish()}.
     *
     * @return the trailer comment, or null if no comment is available.
     */
    public String getTrailerComment() {
        return parser.getTrailerComment();
    }

    /**
     * Tests whether this session is in the failed state. A failed session rejects all further {@code feed} and {@code finish} calls.
     *
     * @return true if this session has failed.
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Tests whether this session has been finished.
     *
     * @return true if {@link #finish()} was called.
     */
    public boolean isFinished() {
        return finished;
    }
}
