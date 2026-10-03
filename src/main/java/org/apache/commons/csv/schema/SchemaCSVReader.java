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
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * A {@link Closeable} reader that splits one CSV stream into named sections.
 *
 * <p>This reader is a pure wrapper around {@link CSVParser}: the underlying CSV
 * syntax (delimiters, quoting, comments, empty lines and so on) is parsed
 * exactly as configured by the supplied {@link CSVFormat}, and no parser
 * grammar is changed. On top of that syntax the reader understands a single
 * reserved record form:</p>
 *
 * <pre>
 * __schema__,&lt;column&gt;,&lt;column&gt;,...
 * </pre>
 *
 * <h2>Sections and declarations</h2>
 * <ul>
 *   <li>A record whose <strong>first decoded field</strong> equals {@link #MARKER}
 *       is a schema declaration. Quoting cannot change this identity: the
 *       comparison runs on the decoded value, so the token quoted or unquoted
 *       declares a schema, while the same characters with a leading space in
 *       the decoded value do not.</li>
 *   <li>Declaration records are consumed internally and are never handed to
 *       business code. Each declaration installs a fresh header for the
 *       records that follow it, so a later section may reorder or rename
 *       columns freely.</li>
 *   <li>The stream starts in section 1 without a header. The first
 *       declaration opens section 1, the second opens section 2, and so on.
 *       Empty sections (a declaration immediately followed by another
 *       declaration) are allowed.</li>
 * </ul>
 *
 * <h2>Errors and the terminal state</h2>
 * <p>Three situations are violations, all reported with the section number and
 * the physical CSV record number:</p>
 * <ul>
 *   <li>{@link SchemaCSVError#DATA_BEFORE_HEADER} a data record appears before
 *       the current section has a valid header.</li>
 *   <li>{@link SchemaCSVError#DUPLICATE_COLUMN} a declaration repeats a column
 *       name, or uses the reserved name {@code __schema__} as a data column.</li>
 *   <li>{@link SchemaCSVError#COLUMN_COUNT_MISMATCH} a data record does not
 *       have exactly as many fields as the section header declares.</li>
 * </ul>
 * <p>When a declaration cannot be installed, or any other violation is found,
 * the reader enters a <strong>terminal state</strong>: the old (now obsolete)
 * header is discarded, and every following read keeps throwing a
 * {@link SchemaCSVError#TERMINATED} {@link SchemaCSVException} that carries the
 * original failure as its cause. Later records can never be bound with a stale
 * header.</p>
 *
 * <h2>Comments and quoting</h2>
 * <p>Comment lines (when the format defines a comment marker) and quoted
 * fields are handled entirely by the underlying parser. Comments are simply
 * skipped during section handling and remain available through
 * {@link SchemaRecord#getComment()} and {@link #getTrailerComment()}.</p>
 *
 * <p>The reader is usable in a for-each loop or through explicit
 * {@link #next()} calls:</p>
 *
 * <pre>{@code
 * try (SchemaCSVReader reader = SchemaCSVReader.of(csvText, CSVFormat.DEFAULT)) {
 *     for (SchemaRecord record : reader) {
 *         System.out.println(record.getSection() + ": " + record.get("name"));
 *     }
 * }
 * }</pre>
 *
 * @since 1.15.0
 */
public final class SchemaCSVReader implements Iterable<SchemaRecord>, Closeable {

    /**
     * The reserved first-field value that marks a schema declaration record.
     */
    public static final String MARKER = "__schema__";

    private final CSVParser parser;

    /** 1-based number of the section currently being read. */
    private long section = 1;

    /** Number of declarations seen so far. */
    private long declarations;

    /** Header names of the current section in column order; null until a valid declaration is installed. */
    private List<String> headerNames;

    /** Data records already delivered in the current section (declarations excluded). */
    private long sectionDataCount;

    /** First failure, retained so all further reads can report termination. */
    private SchemaCSVException failure;

    /** One-record look-ahead shared by next() and iterator(). */
    private SchemaRecord nextRecord;

    private boolean nextFetched;

    private boolean finished;

    /**
     * Constructs a reader over the given input.
     *
     * <p>Any header or skip-header settings carried by {@code format} are
     * ignored, because sections declare their own headers in-stream. All other
     * format settings (delimiter, quoting, comment marker, trimming and so on)
     * are preserved.</p>
     *
     * @param reader the CSV input, must not be null.
     * @param format the CSV format used for all parsing, null maps to {@link CSVFormat#DEFAULT}.
     * @throws IOException if the underlying parser cannot be created.
     */
    public SchemaCSVReader(final Reader reader, final CSVFormat format) throws IOException {
        final CSVFormat base = format == null ? CSVFormat.DEFAULT : format;
        // Never let the raw parser treat a record as a fixed header: sections manage their own headers.
        final CSVFormat effective = base.builder().setHeader((String[]) null).setSkipHeaderRecord(false).get();
        this.parser = CSVParser.parse(Objects.requireNonNull(reader, "reader"), effective);
    }

    /**
     * Creates a reader over CSV text.
     *
     * @param csv the CSV text, must not be null.
     * @param format the CSV format used for all parsing, null maps to {@link CSVFormat#DEFAULT}.
     * @return a new reader.
     * @throws IOException if the underlying parser cannot be created.
     */
    public static SchemaCSVReader of(final String csv, final CSVFormat format) throws IOException {
        return new SchemaCSVReader(new StringReader(Objects.requireNonNull(csv, "csv")), format);
    }

    @Override
    public void close() throws IOException {
        parser.close();
    }

    /**
     * Gets the 1-based number of the current section.
     *
     * <p>The number is the section the next delivered record belongs to: it is
     * 1 before the first declaration and advances as declarations are consumed.</p>
     *
     * @return the current section number.
     */
    public long getSection() {
        return section;
    }

    /**
     * Gets the column names installed for the current section.
     *
     * @return an immutable list of header names in column order, or null if no
     *         valid declaration has been installed yet (for example, before the
     *         first declaration or after a failed one).
     */
    public List<String> getHeaderNames() {
        return headerNames;
    }

    /**
     * Tests whether this reader has entered its terminal state.
     *
     * @return true after a schema violation; false otherwise.
     */
    public boolean isTerminated() {
        return failure != null;
    }

    /**
     * Gets the trailer comment captured after the last record at end of stream, if any.
     *
     * @return the trailer comment, or null if there is none.
     */
    public String getTrailerComment() {
        return parser.getTrailerComment();
    }

    /**
     * Reads the next business data record, skipping {@code __schema__} declarations.
     *
     * @return the next bound {@link SchemaRecord}, or null at the end of the stream.
     * @throws SchemaCSVException on a section violation, or with
     *         {@link SchemaCSVError#TERMINATED} on every call after such a violation.
     * @throws IOException if the underlying parser reports an I/O or lexical failure.
     */
    public SchemaRecord next() throws IOException {
        if (nextFetched) {
            final SchemaRecord cached = nextRecord;
            nextRecord = null;
            nextFetched = false;
            return cached;
        }
        return advance();
    }

    /**
     * Returns an iterator over the remaining data records.
     *
     * <p>A schema violation terminates the iteration with an
     * {@link UncheckedIOException} wrapping the {@link SchemaCSVException}.</p>
     *
     * @return an iterator sharing this reader's stream position.
     */
    @Override
    public Iterator<SchemaRecord> iterator() {
        return new Iterator<SchemaRecord>() {

            @Override
            public boolean hasNext() {
                if (!nextFetched) {
                    try {
                        nextRecord = advance();
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e.getMessage(), e);
                    }
                    nextFetched = true;
                }
                return nextRecord != null;
            }

            @Override
            public SchemaRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more schema records available");
                }
                final SchemaRecord cached = nextRecord;
                nextRecord = null;
                nextFetched = false;
                return cached;
            }
        };
    }

    /**
     * Pulls raw records until the next data record, a violation or EOF.
     */
    private SchemaRecord advance() throws IOException {
        assertNotTerminated();
        if (finished) {
            return null;
        }
        final Iterator<CSVRecord> raw = parser.iterator();
        while (raw.hasNext()) {
            final CSVRecord record = raw.next();
            final long physicalRecordNumber = record.getRecordNumber();
            final String[] fields = record.values();
            if (fields.length > 0 && MARKER.equals(fields[0])) {
                installHeader(physicalRecordNumber, fields);
                continue;
            }
            // Plain data record.
            if (headerNames == null) {
                fail(SchemaCSVError.DATA_BEFORE_HEADER, section, physicalRecordNumber,
                        "Data record " + physicalRecordNumber
                                + " appears before any __schema__ declaration in section " + section);
            }
            if (fields.length != headerNames.size()) {
                fail(SchemaCSVError.COLUMN_COUNT_MISMATCH, section, physicalRecordNumber,
                        "Record " + physicalRecordNumber + " in section " + section + " has " + fields.length
                                + " fields but section header declares " + headerNames.size() + ": " + headerNames);
            }
            sectionDataCount++;
            return new SchemaRecord(section, physicalRecordNumber, sectionDataCount, headerNames, record);
        }
        finished = true;
        return null;
    }

    /**
     * Validates and installs the header carried by a {@code __schema__} declaration.
     *
     * @param recordNumber the physical CSV record number of the declaration.
     * @param fields the declaration fields, whose first field is {@link #MARKER}.
     */
    private void installHeader(final long recordNumber, final String[] fields) throws SchemaCSVException {
        final long newSection = declarations == 0 ? 1 : section + 1;
        final List<String> names = new ArrayList<>(Math.max(0, fields.length - 1));
        final Set<String> seen = new HashSet<>();
        for (int i = 1; i < fields.length; i++) {
            final String name = fields[i];
            if (MARKER.equals(name)) {
                fail(SchemaCSVError.DUPLICATE_COLUMN, newSection, recordNumber,
                        "Schema declaration at record " + recordNumber + " in section " + newSection
                                + " uses reserved column name '" + MARKER + "'");
            }
            // A null value occurs when the format converts its null marker; it cannot be a usable column name.
            if (name == null || !seen.add(name)) {
                fail(SchemaCSVError.DUPLICATE_COLUMN, newSection, recordNumber,
                        "Schema declaration at record " + recordNumber + " in section " + newSection
                                + " contains duplicate or missing column name '" + name + "'");
            }
            names.add(name);
        }
        // Commit the new header atomically only after full validation.
        declarations++;
        section = newSection;
        headerNames = Collections.unmodifiableList(names);
        sectionDataCount = 0;
    }

    private void assertNotTerminated() throws SchemaCSVException {
        if (failure != null) {
            throw new SchemaCSVException(SchemaCSVError.TERMINATED, failure.getSection(), failure.getRecordNumber(),
                    "Reader is terminated after an earlier error at section " + failure.getSection() + ", record "
                            + failure.getRecordNumber() + ": " + failure.getMessage(),
                    failure);
        }
    }

    private void fail(final SchemaCSVError error, final long errorSection, final long recordNumber,
            final String message) throws SchemaCSVException {
        final SchemaCSVException exception = new SchemaCSVException(error, errorSection, recordNumber, message);
        failure = exception;
        // A failed switch must never leave later records bound to the previous header.
        headerNames = null;
        sectionDataCount = 0;
        throw exception;
    }
}
