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
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A push-style, incremental parsing session for CSV input that arrives in small chunks, for example over the network.
 * <p>
 * Text is supplied with {@link #feed(CharSequence)}; each call returns the records that have become complete because of the supplied text. A chunk may end
 * anywhere, including between CR and LF, inside an escaped or doubled quote character, inside a quoted field that spans a line break, between empty fields
 * or in the middle of a multi-character delimiter. Chunks are never stored or re-parsed: input is parsed once, in order.
 * </p>
 * <p>
 * {@link #feed(CharSequence)} never treats the end of a chunk as the end of the input. A record that has not received its line terminator is held back and
 * is never delivered early. {@link #finish()} signals the real end of input; only then is a trailing record without a line terminator delivered.
 * </p>
 * <p>
 * Parsing is performed by a regular {@link CSVParser} using the given {@link CSVFormat}, so every format setting is honored exactly as when parsing from a
 * {@link Reader}, including the delimiter, quote character, escape character, comment marker, empty-line handling, header configuration, surrounding-space
 * handling and all other options. Records returned by this session have the same values, comments, record numbers, character positions and line numbers as
 * records from a {@link CSVParser} over the same text. Record numbers are 1-based and character positions are 0-based, exactly like {@link CSVRecord}.
 * </p>
 *
 * <h2>End of input and repeated calls</h2>
 * <ul>
 * <li>Finishing an empty session returns an empty list.</li>
 * <li>A trailing record without a line terminator is returned by {@link #finish()}.</li>
 * <li>{@link #finish()} is idempotent: after a successful finish, further calls return an empty list.</li>
 * <li>Calling {@link #feed(CharSequence)} after {@link #finish()} throws {@link IllegalStateException}.</li>
 * <li>If a quoted field is not closed when {@link #finish()} is called, {@link #finish()} throws {@link CSVException}.</li>
 * </ul>
 *
 * <h2>Record length limit</h2>
 * <p>
 * A maximum record length in UTF-16 code units can be configured with {@link Builder#setMaxRecordChars(long)}. The length is measured on the raw input, in
 * the same code units as {@code String.length()}, including the line terminator and any quote or escape characters. If a record exceeds the limit, the
 * session enters the failed state: the offending record is not delivered, the call that detects the overflow throws {@link CSVException}, records already
 * returned by previous calls stay valid, and every subsequent {@link #feed(CharSequence)} or {@link #finish()} call is rejected. The limit applies to
 * data records; a header record consumed from the input is not counted.
 * </p>
 * <p>
 * Sessions are single-use and are not thread-safe. Always call {@link #finish()} or {@link #close()} when done.
 * </p>
 *
 * @since 1.15.1
 */
public final class CSVParserSession implements AutoCloseable {

    /**
     * Builds a new {@link CSVParserSession}.
     *
     * @since 1.15.1
     */
    public static final class Builder {

        private CSVFormat format;

        private long maxRecordChars = Long.MAX_VALUE;

        private Builder() {
            // empty
        }

        /**
         * Creates the session.
         * <p>
         * The format is required; a {@code null} format is rejected instead of silently parsing as {@link CSVFormat#DEFAULT}. All options of the given
         * format are honored.
         * </p>
         *
         * @return A new session.
         * @throws NullPointerException     if the format is {@code null}.
         * @throws IllegalArgumentException if {@code maxRecordChars} is less than one.
         */
        public CSVParserSession get() {
            Objects.requireNonNull(format, "format");
            if (maxRecordChars < 1) {
                throw new IllegalArgumentException("maxRecordChars must be at least 1: " + maxRecordChars);
            }
            final CSVParserSession session = new CSVParserSession(format, maxRecordChars);
            session.start();
            return session;
        }

        /**
         * Sets the {@link CSVFormat} used by the session. The format is required and must not be {@code null}.
         *
         * @param format The CSV format.
         * @return {@code this} instance.
         */
        public Builder setFormat(final CSVFormat format) {
            this.format = format;
            return this;
        }

        /**
         * Sets the maximum record length in UTF-16 code units of the raw input, counting the line terminator and quote or escape characters.
         * <p>
         * By default the length is unlimited.
         * </p>
         *
         * @param maxRecordChars The maximum record length, must be positive.
         * @return {@code this} instance.
         * @throws IllegalArgumentException if {@code maxRecordChars} is less than one.
         */
        public Builder setMaxRecordChars(final long maxRecordChars) {
            if (maxRecordChars < 1) {
                throw new IllegalArgumentException("maxRecordChars must be at least 1: " + maxRecordChars);
            }
            this.maxRecordChars = maxRecordChars;
            return this;
        }
    }

    /**
     * The source the internal {@link CSVParser} reads from. It blocks until more text is fed or the session is finished or closed.
     */
    private final class FeedReader extends Reader {

        private long charsDelivered;

        /** Start of the current record, or -1 while the header is being consumed. */
        private long recordBoundary = -1;

        void setRecordBoundary(final long boundary) {
            this.recordBoundary = boundary;
        }

        @Override
        public int read(final char[] buffer, final int offset, final int length) throws IOException {
            final ReentrantLock sessionLock = CSVParserSession.this.lock;
            sessionLock.lock();
            try {
                while (pendingChars == 0 && !endOfInput && state == State.PARSING) {
                    waitingForData = true;
                    changed.signalAll();
                    try {
                        changed.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        final InterruptedIOException ioException = new InterruptedIOException("Interrupted while waiting for CSV input");
                        ioException.initCause(e);
                        throw ioException;
                    }
                }
                waitingForData = false;
                if (state == State.CLOSED) {
                    throw new IOException("CSVParserSession is closed");
                }
                if (state == State.FAILED) {
                    throw new IOException("CSVParserSession has failed", failure);
                }
                if (pendingChars == 0) {
                    return -1;
                }
                final long remaining = recordBoundary < 0 ? Long.MAX_VALUE : maxRecordChars - (charsDelivered - recordBoundary);
                if (remaining <= 0) {
                    throw new CSVException("Record starting at character position %,d exceeds the maximum length of %,d UTF-16 code units",
                            Long.valueOf(recordBoundary), Long.valueOf(maxRecordChars));
                }
                final int count = (int) Math.min(length, Math.min(pendingChars, remaining));
                int copied = 0;
                while (copied < count) {
                    final char[] first = chunks.peekFirst();
                    final int available = first.length - chunkOffset;
                    final int n = Math.min(available, count - copied);
                    System.arraycopy(first, chunkOffset, buffer, offset + copied, n);
                    chunkOffset += n;
                    copied += n;
                    if (chunkOffset == first.length) {
                        chunks.pollFirst();
                        chunkOffset = 0;
                    }
                }
                pendingChars -= count;
                charsDelivered += count;
                changed.signalAll();
                return count;
            } finally {
                sessionLock.unlock();
            }
        }

        @Override
        public void close() {
            // The session owns this reader; closing the parser must not end the session.
        }
    }

    private enum State {

        /** Accepting input. */
        PARSING,

        /** {@link #finish()} has been called and the parser consumed the whole input. */
        FINISHED,

        /** Parsing failed. */
        FAILED,

        /** {@link #close()} was called. */
        CLOSED
    }

    private static final AtomicInteger WORKER_COUNT = new AtomicInteger();

    /**
     * Creates a new builder.
     *
     * @return A new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new session for the given format.
     *
     * @param format The CSV format, must not be {@code null}.
     * @return A new session.
     * @throws NullPointerException if the format is {@code null}.
     */
    public static CSVParserSession create(final CSVFormat format) {
        return builder().setFormat(format).get();
    }

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition changed = lock.newCondition();

    private final FeedReader feedReader = new FeedReader();

    private final long maxRecordChars;

    private final CSVFormat format;

    private final Thread worker;

    /** Fed chunks not yet consumed by the parser. */
    private final ArrayDeque<char[]> chunks = new ArrayDeque<>();

    /** Records the parser finished and the caller has not collected yet. */
    private final ArrayList<CSVRecord> produced = new ArrayList<>();

    private int chunkOffset;

    private long pendingChars;

    private boolean endOfInput;

    private boolean waitingForData;

    private boolean headersReady;

    private State state = State.PARSING;

    private Throwable failure;

    private CSVParser parser;

    private List<String> headerNames = Collections.emptyList();

    private Map<String, Integer> headerMap;

    private String headerComment;

    private String trailerComment;

    private CSVParserSession(final CSVFormat format, final long maxRecordChars) {
        this.format = format.copy();
        this.maxRecordChars = maxRecordChars;
        this.worker = new Thread(this::runParser, "commons-csv-push-parser-" + WORKER_COUNT.incrementAndGet());
        this.worker.setDaemon(true);
    }

    /**
     * Waits until the parser has consumed every supplied character and is either waiting for more input or done.
     * <p>
     * Must be called while holding {@link #lock}.
     * </p>
     */
    private void awaitQuiescentLocked() throws CSVException {
        try {
            while (failure == null && state != State.FINISHED && state != State.CLOSED &&
                    !(waitingForData && pendingChars == 0)) {
                changed.await();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            failLocked(e);
            throw interruptedException(e);
        }
    }

    /**
     * Aborts the session, releasing the internal parser thread.
     * <p>
     * This method is idempotent. After it is called, {@link #feed(CharSequence)} and {@link #finish()} throw {@link IllegalStateException}.
     * </p>
     */
    @Override
    public void close() {
        final Thread thread;
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            changed.signalAll();
            thread = worker;
        } finally {
            lock.unlock();
        }
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<CSVRecord> drainProducedLocked() {
        if (produced.isEmpty()) {
            return Collections.emptyList();
        }
        final List<CSVRecord> result = new ArrayList<>(produced);
        produced.clear();
        return result;
    }

    private void failLocked(final Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        headersReady = true;
        if (state == State.PARSING) {
            state = State.FAILED;
        }
        changed.signalAll();
    }

    /**
     * Feeds the next chunk of input and returns the records that became complete with this chunk.
     * <p>
     * The method blocks until the parser has consumed the chunk. A chunk ending in the middle of a record does not deliver that record. The end of a chunk is
     * never treated as the end of input; only {@link #finish()} signals EOF. An empty chunk returns an empty list.
     * </p>
     *
     * @param chunk The next chunk of CSV input, must not be {@code null}.
     * @return A new list of the records completed by this chunk, possibly empty.
     * @throws CSVException if invalid CSV is detected or a record exceeds the configured maximum length.
     * @throws NullPointerException if {@code chunk} is {@code null}.
     * @throws IllegalStateException if the session already finished, failed or was closed.
     */
    public List<CSVRecord> feed(final CharSequence chunk) throws CSVException {
        Objects.requireNonNull(chunk, "chunk");
        lock.lock();
        try {
            if (state == State.FAILED) {
                throw new IllegalStateException("CSVParserSession has failed", failure);
            }
            if (state == State.CLOSED) {
                throw new IllegalStateException("CSVParserSession is closed");
            }
            if (endOfInput) {
                throw new IllegalStateException("CSVParserSession is finished");
            }
            final int length = chunk.length();
            if (length > 0) {
                final char[] chars = new char[length];
                if (chunk instanceof String) {
                    ((String) chunk).getChars(0, length, chars, 0);
                } else {
                    for (int i = 0; i < length; i++) {
                        chars[i] = chunk.charAt(i);
                    }
                }
                chunks.addLast(chars);
                pendingChars += length;
                changed.signalAll();
                awaitQuiescentLocked();
            }
            if (failure != null) {
                throw toCSVException(failure);
            }
            return drainProducedLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals the end of input and returns the remaining complete records.
     * <p>
     * Only this call signals EOF: a trailing record without a line terminator is delivered here. Finishing an empty session returns an empty list, and so do
     * further calls after a successful finish. A quoted field still open at the end of input makes this call throw {@link CSVException}.
     * </p>
     *
     * @return A new list of the records completed by the remaining input, possibly empty.
     * @throws CSVException if invalid CSV is detected, for example a quoted field that is not closed, or a record exceeds the configured maximum length.
     * @throws IllegalStateException if the session failed or was closed.
     */
    public List<CSVRecord> finish() throws CSVException {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                throw new IllegalStateException("CSVParserSession is closed");
            }
            if (state == State.FAILED) {
                throw toCSVException(failure);
            }
            if (endOfInput) {
                return Collections.emptyList();
            }
            endOfInput = true;
            changed.signalAll();
            try {
                while (failure == null && state != State.FINISHED && state != State.CLOSED) {
                    changed.await();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                failLocked(e);
                throw interruptedException(e);
            }
            if (failure != null) {
                throw toCSVException(failure);
            }
            return drainProducedLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current character position in the input consumed so far.
     * <p>
     * Positions are 0-based and match {@link CSVParser#getCurrentLineNumber()} semantics over the same text.
     * </p>
     *
     * @return The number of UTF-16 code units consumed so far, {@code 0} before the first record.
     */
    public long getCharacterPosition() {
        lock.lock();
        try {
            return parser != null ? parser.getCharacterPosition() : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current line number, the same value a {@link CSVParser} would report.
     * <p>
     * With multi-line quoted values the line number does not correspond to the record number.
     * </p>
     *
     * @return The current 1-based line number, {@code 0} before the first record.
     */
    public long getCurrentLineNumber() {
        lock.lock();
        try {
            return parser != null ? parser.getCurrentLineNumber() : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the first end-of-line sequence encountered, or {@code null} before any end of line.
     *
     * @return The first end-of-line sequence.
     */
    public String getFirstEndOfLine() {
        lock.lock();
        try {
            return parser != null ? parser.getFirstEndOfLine() : null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the header comment, if the comment preceding the header record was consumed.
     *
     * @return The header comment, or {@code null}.
     */
    public String getHeaderComment() {
        lock.lock();
        try {
            awaitHeadersLocked();
            return headerComment;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the header map, blocked until the header has been consumed.
     *
     * @return A copy of the header map (values are 0-based column indices), or {@code null} if the format defines no header.
     */
    public Map<String, Integer> getHeaderMap() {
        lock.lock();
        try {
            awaitHeadersLocked();
            return headerMap;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the header names in column order, blocked until the header has been consumed.
     *
     * @return An unmodifiable list of header names.
     */
    public List<String> getHeaderNames() {
        lock.lock();
        try {
            awaitHeadersLocked();
            return headerNames;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the number of complete records delivered so far.
     * <p>
     * Record numbers are 1-based, so this is the number of the last delivered record and {@code 0} before the first one.
     * </p>
     *
     * @return The number of complete records, starting at {@code 1} for the first record.
     */
    public long getRecordNumber() {
        lock.lock();
        try {
            return parser != null ? parser.getRecordNumber() : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the trailer comment, available only after a successful {@link #finish()}.
     *
     * @return The trailer comment, or {@code null}.
     */
    public String getTrailerComment() {
        lock.lock();
        try {
            return trailerComment;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tests whether the session is in the failed state.
     *
     * @return {@code true} after a parsing or length-limit failure.
     */
    public boolean isFailed() {
        lock.lock();
        try {
            return state == State.FAILED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tests whether {@link #finish()} was called and the whole input consumed successfully.
     *
     * @return {@code true} after a successful finish.
     */
    public boolean isFinished() {
        lock.lock();
        try {
            return state == State.FINISHED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until the header has been consumed or the parser has terminated.
     * <p>
     * Must be called while holding {@link #lock}.
     * </p>
     */
    private void awaitHeadersLocked() {
        boolean interrupted = false;
        while (!headersReady && state == State.PARSING) {
            try {
                changed.await();
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the CSV header");
        }
        if (parser == null) {
            throw new IllegalStateException("CSVParserSession ended before the header was parsed", failure);
        }
    }

    private CSVException interruptedException(final InterruptedException e) {
        final CSVException exception = new CSVException("Interrupted while waiting for the parser to consume CSV input");
        exception.initCause(e);
        return exception;
    }

    /**
     * Runs the parser. This is executed on a single dedicated thread, one read at a time, so no input is ever re-parsed.
     */
    private void runParser() {
        CSVParser newParser = null;
        try {
            newParser = CSVParser.builder().setReader(feedReader).setFormat(format)
                    .setCharset((Charset) null).setTrackBytes(false).get();
            lock.lock();
            try {
                parser = newParser;
                headerMap = newParser.getHeaderMap();
                headerNames = newParser.getHeaderNames();
                headerComment = newParser.getHeaderComment();
                feedReader.setRecordBoundary(newParser.getCharacterPosition());
                headersReady = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            while (true) {
                final CSVRecord record = newParser.nextRecord();
                if (record == null) {
                    break;
                }
                lock.lock();
                try {
                    produced.add(record);
                    feedReader.setRecordBoundary(newParser.getCharacterPosition());
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
            lock.lock();
            try {
                trailerComment = newParser.getTrailerComment();
                if (state == State.PARSING) {
                    state = State.FINISHED;
                }
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        } catch (final Throwable t) {
            lock.lock();
            try {
                failLocked(t);
            } finally {
                lock.unlock();
            }
        }
    }

    private void start() {
        worker.start();
    }

    private static CSVException toCSVException(final Throwable throwable) {
        if (throwable instanceof CSVException) {
            return (CSVException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        final CSVException exception = new CSVException("CSV parsing failed: %s", throwable);
        exception.initCause(throwable);
        return exception;
    }
}
