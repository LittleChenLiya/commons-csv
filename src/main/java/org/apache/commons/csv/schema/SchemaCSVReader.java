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

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * A wrapper around {@link CSVParser} that splits a CSV stream into schema-declared sections.
 *
 * <h2>Sections and schema declarations</h2>
 * <p>
 * A section is introduced by a record whose first field is the reserved marker
 * {@link #SCHEMA __schema__}. The remaining fields of that declaration name the columns of
 * the new section. The declaration record is consumed by this reader and is never delivered
 * to business code. Every following data record is delivered as a {@link SchemaRecord}
 * bound to the header of the section it belongs to.
 * </p>
 *
 * <pre>
 * __schema__,name,age
 * a,1
 * __schema__,age,name
 * 2,b
 * </pre>
 *
 * <p>
 * In the example above, {@code a,1} is delivered in section 1 with {@code name=a} and
 * {@code age=1}, while {@code 2,b} is delivered in section 2 with {@code age=2} and
 * {@code name=b}. Each record resolves column names against the header of its own section.
 * </p>
 *
 * <h2>Reserved marker</h2>
 * <p>
 * {@code __schema__} is a reserved field. Its identity is determined by the
 * <em>decoded</em> field value: a quoted {@code "__schema__"} still declares a section,
 * because quotes are removed by the parser. Object identity is never used.
 * </p>
 *
 * <h2>Termination</h2>
 * <p>
 * The reader terminates if:
 * </p>
 * <ul>
 * <li>a data record appears before any schema declaration,</li>
 * <li>a schema declaration declares a duplicate column name (or no column names at all),
 * or</li>
 * <li>a data record's field count does not match the header of its section.</li>
 * </ul>
 * <p>
 * After termination no further records are delivered, {@link #hasNext()} returns
 * {@code false}, and every {@link #next()} call reports the termination. The underlying
 * parser is not advanced past the failure, so later data can never be bound to a stale
 * header. The causing exception is available through {@link #getTerminalError()}.
 * </p>
 *
 * <h2>Format requirements</h2>
 * <p>
 * The wrapped {@link CSVFormat} must not define a header (that is,
 * {@link CSVFormat#getHeader()} must be {@code null}). Headers are owned by the sections
 * and declared in the stream. Comments and quoted fields are parsed exactly as the format
 * configures; this reader only interprets the decoded field values.
 * </p>
 *
 * <h2>Empty sections</h2>
 * <p>
 * A section may be empty: a declaration may be followed immediately by another declaration
 * or by end of stream without producing any data record.
 * </p>
 *
 * @see SchemaRecord
 * @see SchemaCSVException
 * @since 1.0
 */
public final class SchemaCSVReader implements Iterable<SchemaRecord>, Iterator<SchemaRecord>, Closeable {

    /**
     * The reserved first-field value that declares a new section.
     * <p>
     * Identity is based on the decoded field value, so a quoted {@code "__schema__"} is
     * still recognized.
     * </p>
     */
    public static final String SCHEMA = "__schema__";

    /** The underlying parser. */
    private final CSVParser parser;

    /** Iterator over the raw records of the underlying parser. */
    private final Iterator<CSVRecord> iterator;

    /** The current section number (1-based); 0 before the first declaration. */
    private int sectionNumber;

    /** The header names of the current section, in column order. */
    private List<String> headerNames;

    /** Mapping of column name to 0-based index within the current section. */
    private Map<String, Integer> headerIndex;

    /** Whether the reader has entered the terminated state. */
    private boolean terminated;

    /** The exception that caused termination, or null. */
    private SchemaCSVException terminalError;

    /** The next record, buffered between {@link #hasNext()} and {@link #next()}. */
    private SchemaRecord current;

    /** A comment carried over from a schema declaration to the next data record. */
    private String pendingComment;

    /**
     * Creates a new reader.
     *
     * @param reader the source reader; must not be {@code null}.
     * @param format the CSV format; must not define a header.
     * @throws IOException if an I/O error occurs.
     * @throws IllegalArgumentException if the format defines a header.
     */
    public SchemaCSVReader(final Reader reader, final CSVFormat format) throws IOException {
        this(CSVParser.parse(reader, Objects.requireNonNull(format, "format")));
    }

    /**
     * Creates a new reader wrapping an existing {@link CSVParser}.
     *
     * @param parser the parser to wrap; must not be {@code null}.
     * @throws IllegalArgumentException if the header of the parser format is defined.
     */
    public SchemaCSVReader(final CSVParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
        if (parser.getHeaderMap() != null) {
            try {
                parser.close();
            } catch (final IOException ignored) {
                // Closing a parser we could not use; nothing useful to do.
            }
            throw new IllegalArgumentException(
                    "The CSVFormat must not define a header; sections are declared by " + SCHEMA + " records in the stream");
        }
        this.iterator = parser.iterator();
    }

    /**
     * Gets the current section number.
     *
     * @return the section number, 1-based; {@code 0} before the first declaration.
     */
    public int getSectionNumber() {
        return sectionNumber;
    }

    /**
     * Gets the header names of the current section, in column order.
     *
     * @return an unmodifiable list of header names; empty if no section is active.
     */
    public List<String> getHeaderNames() {
        return headerNames != null ? headerNames : Collections.emptyList();
    }

    /**
     * Gets the exception that caused this reader to terminate.
     *
     * @return the terminal error, or {@code null} if the reader has not terminated.
     */
    public SchemaCSVException getTerminalError() {
        return terminalError;
    }

    /**
     * Tests whether this reader has entered the terminated state.
     *
     * @return {@code true} if terminated.
     */
    public boolean isTerminated() {
        return terminated;
    }

    /**
     * Gets the next data record.
     *
     * @return the next {@link SchemaRecord}, or {@code null} at end of stream.
     */
    private SchemaRecord nextRecord() {
        while (iterator.hasNext()) {
            final CSVRecord record = iterator.next();
            final String firstField = record.size() > 0 ? record.get(0) : null;
            if (SCHEMA.equals(firstField)) {
                processSchema(record);
                if (terminated) {
                    return null;
                }
                // Declaration records are consumed here and never delivered.
            } else {
                return bindRecord(record);
            }
        }
        return null;
    }

    /**
     * Processes a schema declaration record.
     *
     * @param record the declaration record.
     */
    private void processSchema(final CSVRecord record) {
        final int newSectionNumber = sectionNumber + 1;
        final int columnCount = record.size() - 1;
        if (columnCount <= 0) {
            terminate(new SchemaCSVException(
                    String.format("Schema declaration for section %d has no column names", Integer.valueOf(newSectionNumber)),
                    newSectionNumber, parser.getRecordNumber()));
            return;
        }
        final Map<String, Integer> index = new LinkedHashMap<>(columnCount);
        final List<String> names = new ArrayList<>(columnCount);
        for (int i = 1; i < record.size(); i++) {
            final String name = record.get(i);
            if (index.containsKey(name)) {
                terminate(new SchemaCSVException(
                        String.format("Duplicate column name \"%s\" in schema declaration for section %d", name,
                                Integer.valueOf(newSectionNumber)),
                        newSectionNumber, parser.getRecordNumber()));
                return;
            }
            index.put(name, Integer.valueOf(i - 1));
            names.add(name);
        }
        sectionNumber = newSectionNumber;
        headerNames = Collections.unmodifiableList(names);
        headerIndex = index;
        pendingComment = record.getComment();
    }

    /**
     * Binds a data record to the header of the current section.
     *
     * @param record the raw data record.
     * @return the bound record, or {@code null} if the reader terminated.
     */
    private SchemaRecord bindRecord(final CSVRecord record) {
        if (headerIndex == null) {
            terminate(new SchemaCSVException(
                    String.format("Data record appears before the first %s declaration", SCHEMA), 0,
                    parser.getRecordNumber()));
            return null;
        }
        if (record.size() != headerNames.size()) {
            terminate(new SchemaCSVException(
                    String.format("Column count mismatch in section %d: record has %d fields but the section header has %d columns",
                            Integer.valueOf(sectionNumber), Integer.valueOf(record.size()), Integer.valueOf(headerNames.size())),
                    sectionNumber, parser.getRecordNumber()));
            return null;
        }
        final SchemaRecord result = new SchemaRecord(sectionNumber, parser.getRecordNumber(), record, headerNames,
                headerIndex, pendingComment);
        pendingComment = null;
        return result;
    }

    /**
     * Enters the terminated state, recording the error that caused it.
     *
     * @param error the terminal error.
     */
    private void terminate(final SchemaCSVException error) {
        terminated = true;
        terminalError = error;
        current = null;
    }

    /**
     * Returns an exception reporting the termination.
     *
     * @return a {@link NoSuchElementException} whose cause is the terminal error.
     */
    private NoSuchElementException terminationException() {
        final NoSuchElementException exception = new NoSuchElementException(
                "SchemaCSVReader terminated after an error: " + terminalError.getMessage());
        exception.initCause(terminalError);
        return exception;
    }

    @Override
    public boolean hasNext() {
        if (terminated) {
            return false;
        }
        if (current == null) {
            current = nextRecord();
        }
        return current != null;
    }

    @Override
    public SchemaRecord next() {
        if (terminated) {
            throw terminationException();
        }
        SchemaRecord next = current;
        current = null;
        if (next == null) {
            next = nextRecord();
            if (next == null) {
                if (terminated) {
                    throw terminationException();
                }
                throw new NoSuchElementException("No more CSV records");
            }
        }
        return next;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<SchemaRecord> iterator() {
        return this;
    }

    /**
     * Parses the remaining input and returns all data records.
     *
     * @return a list of {@link SchemaRecord}, may be empty.
     */
    public List<SchemaRecord> getRecords() {
        return stream().collect(Collectors.toList());
    }

    /**
     * Returns a sequential {@code Stream} of the data records.
     *
     * @return a stream of {@link SchemaRecord}.
     */
    public Stream<SchemaRecord> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED), false);
    }

    @Override
    public void close() throws IOException {
        parser.close();
    }
}
