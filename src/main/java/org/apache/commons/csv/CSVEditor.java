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
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Edits individual cells of CSV text in place, preserving the original formatting of everything else.
 * <p>
 * Unlike parsing a document into {@link CSVRecord}s and printing it back with a {@link CSVPrinter}, which reprints
 * every value, this class locates the exact character span of each targeted field by running the real {@link Lexer}
 * over the input and splices in the replacement values only. Untouched fields, comments, empty lines, line
 * terminator styles (CR, LF, or CRLF), and the presence or absence of a final line terminator are preserved
 * character for character.
 * </p>
 * <p>
 * Cells are addressed by record index and column index, both zero-based. Record indexes count the records that
 * {@link CSVParser} would return when iterating, so a header record consumed by the format (for example through
 * {@link CSVFormat.Builder#setHeader()} or {@link CSVFormat.Builder#setSkipHeaderRecord(boolean)}) is not counted.
 * </p>
 * <p>
 * Replacement values are printed with the quoting and escaping rules of the configured {@link CSVFormat}, as if
 * printed by {@link CSVFormat#print(Object, Appendable, boolean)}; a {@code null} value is printed as the null
 * string of the format. If the format can neither quote nor escape, replacement values must not contain characters
 * the format cannot represent (such as the delimiter or line breaks), or the resulting text will not round-trip.
 * </p>
 * <p>
 * Setting the same cell twice replaces the previous edit; the last value wins.
 * </p>
 * <p>
 * Usage:
 * </p>
 * <pre>{@code
 * String edited = CSVEditor.of(CSVFormat.DEFAULT)
 *         .set(0, 1, "new value")
 *         .set(2, 0, "other")
 *         .apply(csvText);
 * }</pre>
 *
 * @since 1.15.0
 */
public final class CSVEditor {

    /**
     * Identifies a cell by record index and column index.
     */
    private static final class Cell {

        private final long recordIndex;

        private final int columnIndex;

        Cell(final long recordIndex, final int columnIndex) {
            this.recordIndex = recordIndex;
            this.columnIndex = columnIndex;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Cell)) {
                return false;
            }
            final Cell other = (Cell) obj;
            return recordIndex == other.recordIndex && columnIndex == other.columnIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordIndex, columnIndex);
        }
    }

    /**
     * A single splice: replace {@code input[start, end)} with {@code text}.
     */
    private static final class Replacement {

        private final int start;

        private final int end;

        private final String text;

        Replacement(final int start, final int end, final String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /**
     * Creates a new editor using the given format to locate and print values.
     *
     * @param format the CSV format, not null.
     * @return a new editor.
     */
    public static CSVEditor of(final CSVFormat format) {
        return new CSVEditor(format);
    }

    private final CSVFormat format;

    /** Edits in insertion order; a repeated edit of the same cell overrides the previous one. */
    private final Map<Cell, Object> edits = new LinkedHashMap<>();

    private CSVEditor(final CSVFormat format) {
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * Adds the raw span of the current token to the record, mirroring how {@link CSVParser} drops the empty field
     * produced by an actual trailing delimiter when {@link CSVFormat#getTrailingDelimiter()} is set.
     *
     * @param spans      the spans collected so far for the current record.
     * @param token      the current token.
     * @param lastRecord whether this token ends the record.
     */
    private void addSpan(final List<long[]> spans, final Token token, final boolean lastRecord) {
        final String input = format.trim(token.content.toString());
        if (lastRecord && input.isEmpty() && format.getTrailingDelimiter() && !token.isQuoted) {
            return;
        }
        spans.add(new long[] { token.startOffset, token.endOffset });
    }

    /**
     * Applies all edits to the given CSV text.
     *
     * @param input the complete CSV text, not null.
     * @return the edited CSV text; every character outside the edited cells is preserved as-is.
     * @throws IndexOutOfBoundsException if an edit addresses a record or column that does not exist.
     * @throws IOException               if the input cannot be parsed or a replacement value cannot be printed.
     */
    public String apply(final String input) throws IOException {
        Objects.requireNonNull(input, "input");
        if (edits.isEmpty()) {
            return input;
        }
        final List<long[][]> records = readSpans(input);
        // Resolve and validate every edit before changing anything.
        final List<Replacement> replacements = new ArrayList<>(edits.size());
        for (final Map.Entry<Cell, Object> edit : edits.entrySet()) {
            final Cell cell = edit.getKey();
            if (cell.recordIndex >= records.size()) {
                throw new IndexOutOfBoundsException(
                        "Record index " + cell.recordIndex + " is out of bounds for " + records.size() + " record(s)");
            }
            final long[][] record = records.get((int) cell.recordIndex);
            if (cell.columnIndex >= record.length) {
                throw new IndexOutOfBoundsException("Column index " + cell.columnIndex + " is out of bounds for " +
                        record.length + " column(s) in record " + cell.recordIndex);
            }
            final long[] span = record[cell.columnIndex];
            final StringBuilder printed = new StringBuilder();
            format.print(edit.getValue(), printed, true);
            replacements.add(new Replacement(Math.toIntExact(span[0]), Math.toIntExact(span[1]), printed.toString()));
        }
        replacements.sort((first, second) -> Integer.compare(first.start, second.start));
        final StringBuilder result = new StringBuilder(input.length() + 64);
        int position = 0;
        for (final Replacement replacement : replacements) {
            result.append(input, position, replacement.start);
            result.append(replacement.text);
            position = replacement.end;
        }
        result.append(input, position, input.length());
        return result.toString();
    }

    /**
     * Tests whether the format consumes a header record, which is then not counted as record 0.
     *
     * @return true if the first parsed record is a header.
     */
    private boolean isHeaderRecordSkipped() {
        final String[] header = format.getHeader();
        return header != null && (header.length == 0 || format.getSkipHeaderRecord());
    }

    /**
     * Parses the input and collects the raw character span of every field of every record, in the same order and
     * with the same record boundaries as {@link CSVParser} iteration.
     *
     * @param input the complete CSV text.
     * @return one entry per record, each holding one {@code [startOffset, endOffset]} pair per field.
     * @throws IOException if the input cannot be parsed.
     */
    private List<long[][]> readSpans(final String input) throws IOException {
        final List<long[][]> records = new ArrayList<>();
        try (Lexer lexer = new Lexer(format, new ExtendedBufferedReader(new StringReader(input)))) {
            final Token token = new Token();
            boolean skipHeader = isHeaderRecordSkipped();
            boolean eof = false;
            while (!eof) {
                final List<long[]> spans = new ArrayList<>();
                do {
                    token.reset();
                    lexer.nextToken(token);
                    switch (token.type) {
                    case TOKEN:
                        addSpan(spans, token, false);
                        break;
                    case EORECORD:
                        addSpan(spans, token, true);
                        break;
                    case EOF:
                        eof = true;
                        if (token.isReady) {
                            addSpan(spans, token, true);
                        }
                        break;
                    case INVALID:
                        throw new CSVException("(line %,d) invalid parse sequence", lexer.getCurrentLineNumber());
                    case COMMENT: // Ignored currently
                        token.type = Token.Type.TOKEN; // Read another token
                        break;
                    default:
                        throw new CSVException("Unexpected Token type: %s", token.type);
                    }
                } while (token.type == Token.Type.TOKEN);
                if (!spans.isEmpty()) {
                    if (skipHeader) {
                        skipHeader = false;
                    } else {
                        records.add(spans.toArray(new long[0][]));
                    }
                }
            }
        }
        return records;
    }

    /**
     * Sets the replacement value for the cell at the given record and column index. Calling this method again for
     * the same cell overrides the previous edit.
     *
     * @param recordIndex the zero-based record index, as counted when iterating a {@link CSVParser}.
     * @param columnIndex the zero-based column index within the record.
     * @param value       the new value; printed with the format of this editor, {@code null} prints the null string
     *                    of the format.
     * @return this editor, for chaining.
     * @throws IndexOutOfBoundsException if either index is negative.
     */
    public CSVEditor set(final long recordIndex, final int columnIndex, final Object value) {
        if (recordIndex < 0) {
            throw new IndexOutOfBoundsException("Record index must not be negative: " + recordIndex);
        }
        if (columnIndex < 0) {
            throw new IndexOutOfBoundsException("Column index must not be negative: " + columnIndex);
        }
        edits.put(new Cell(recordIndex, columnIndex), value);
        return this;
    }
}
