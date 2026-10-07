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
 * Edits the values of individual CSV fields in existing CSV text while preserving the original formatting.
 * <p>
 * The input is parsed with the supplied {@link CSVFormat}, so field addresses correspond to the records and columns
 * that {@link CSVParser} produces: records and columns are zero based, comment lines and ignored empty lines are not
 * records, and multi-line quoted fields count as a single field. Only the raw text of an edited field is rewritten;
 * every other character of the input, including comments, empty lines, the original quoting style and the
 * line-ending style ({@code \n}, {@code \r} or {@code \r\n}), is copied character for character.
 * </p>
 * <p>
 * Example:
 * </p>
 * <pre>{@code
 * String edited = CSVEditor.edit(csv, CSVFormat.DEFAULT,
 *         CSVEditor.cell(0, 1, "new value"),
 *         CSVEditor.cell(2, 0, ""));
 * }</pre>
 * <p>
 * Edited values are emitted bare when possible and otherwise quoted or escaped according to the format. A value that
 * needs quoting or escaping but can be represented neither with a quote character nor with an escape character causes
 * a {@link CSVException}.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVEditor {

    /**
     * A single field edit, addressed by zero-based record index and zero-based column index.
     */
    public static final class CellEdit {

        private final long record;
        private final int column;
        private final String value;

        /**
         * Constructs a new {@code CellEdit}.
         *
         * @param record the zero-based record index as produced by {@link CSVParser}.
         * @param column the zero-based column index within the record.
         * @param value  the new value; {@code null} is treated as the empty string.
         */
        public CellEdit(final long record, final int column, final String value) {
            if (record < 0) {
                throw new IllegalArgumentException("record index must not be negative: " + record);
            }
            if (column < 0) {
                throw new IllegalArgumentException("column index must not be negative: " + column);
            }
            this.record = record;
            this.column = column;
            this.value = Objects.toString(value, "");
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CellEdit)) {
                return false;
            }
            final CellEdit other = (CellEdit) obj;
            return record == other.record && column == other.column;
        }

        /**
         * Gets the zero-based column index.
         *
         * @return the column index.
         */
        public int getColumn() {
            return column;
        }

        /**
         * Gets the zero-based record index.
         *
         * @return the record index.
         */
        public long getRecord() {
            return record;
        }

        /**
         * Gets the replacement value.
         *
         * @return the replacement value, never null.
         */
        public String getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(record) * 31 + column;
        }

        @Override
        public String toString() {
            return "CellEdit[record=" + record + ", column=" + column + ", value=" + value + "]";
        }
    }

    private CSVEditor() {
    }

    /**
     * Applies the given field edits to CSV text without altering any surrounding content.
     * <p>
     * When more than one edit targets the same record and column, the edit that appears last in the argument list
     * wins.
     * </p>
     *
     * @param csvText the complete original CSV text, must not be null.
     * @param format  the CSV format used to parse and re-encode the text, must not be null.
     * @param edits   the field edits to apply.
     * @return the resulting text, identical to the input apart from the targeted fields.
     * @throws CSVException if an edit addresses a record or column that does not exist, the input is invalid CSV, or a
     *                      new value cannot be encoded with the given format.
     */
    public static String edit(final String csvText, final CSVFormat format, final CellEdit... edits)
            throws CSVException {
        Objects.requireNonNull(csvText, "csvText");
        Objects.requireNonNull(format, "format");
        final Map<Long, Map<Integer, String>> byRecord = groupEdits(edits);
        if (byRecord.isEmpty()) {
            return csvText;
        }
        final List<int[]> records = locateSpans(csvText, format, byRecord);
        return applyEdits(csvText, records, byRecord, format);
    }

    /**
     * Applies the given field edits to CSV text without altering any surrounding content.
     *
     * @param csvText the complete original CSV text, must not be null.
     * @param format  the CSV format used to parse and re-encode the text, must not be null.
     * @param edits   the field edits to apply, must not be null.
     * @return the resulting text, identical to the input apart from the targeted fields.
     * @throws CSVException if an edit addresses a record or column that does not exist, the input is invalid CSV, or a
     *                      new value cannot be encoded with the given format.
     */
    public static String edit(final String csvText, final CSVFormat format, final Iterable<CellEdit> edits)
            throws CSVException {
        Objects.requireNonNull(csvText, "csvText");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(edits, "edits");
        final Map<Long, Map<Integer, String>> byRecord = groupEdits(edits);
        if (byRecord.isEmpty()) {
            return csvText;
        }
        final List<int[]> records = locateSpans(csvText, format, byRecord);
        return applyEdits(csvText, records, byRecord, format);
    }

    /**
     * Creates a field edit.
     *
     * @param record the zero-based record index.
     * @param column the zero-based column index.
     * @param value  the new value; {@code null} is treated as the empty string.
     * @return a new {@code CellEdit}.
     */
    public static CellEdit cell(final long record, final int column, final String value) {
        return new CellEdit(record, column, value);
    }

    private static String applyEdits(final String csvText, final List<int[]> records,
            final Map<Long, Map<Integer, String>> byRecord, final CSVFormat format) throws CSVException {
        final StringBuilder out = new StringBuilder(csvText.length());
        int cursor = 0;
        for (int r = 0; r < records.size(); r++) {
            final Map<Integer, String> columnEdits = byRecord.get((long) r);
            if (columnEdits == null) {
                continue;
            }
            final int[] recordSpans = records.get(r);
            for (final Map.Entry<Integer, String> entry : columnEdits.entrySet()) {
                final int column = entry.getKey();
                if (column >= recordSpans.length / 2) {
                    throw new CSVException("Column %,d does not exist in record %,d, which has %,d column(s)",
                            column, r, recordSpans.length / 2);
                }
                final int spanIndex = column * 2;
                final int start = recordSpans[spanIndex];
                final int end = recordSpans[spanIndex + 1];
                out.append(csvText, cursor, start);
                out.append(encodeValue(entry.getValue(), format));
                cursor = end;
            }
        }
        out.append(csvText, cursor, csvText.length());
        return out.toString();
    }

    private static String encodeEscaped(final String value, final char escape, final String delimiter,
            final Character commentStart) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            if (requiresEscape(c, escape, delimiter, commentStart)) {
                out.append(escape);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean requiresEscape(final char c, final char escape, final String delimiter,
            final Character commentStart) {
        if (c == escape || c == '\r' || c == '\n' || delimiter.indexOf(c) >= 0) {
            return true;
        }
        return commentStart != null && c == commentStart.charValue();
    }

    private static String encodeValue(final String value, final CSVFormat format) throws CSVException {
        if (!needsQuoting(value, format)) {
            return value;
        }
        final Character quote = format.getQuoteCharacter();
        if (quote != null) {
            return quoteValue(value, quote.charValue(), format.getEscapeCharacter());
        }
        final Character escape = format.getEscapeCharacter();
        if (escape != null) {
            return encodeEscaped(value, escape.charValue(), format.getDelimiterString(), format.getCommentMarker());
        }
        throw new CSVException("Cannot encode value %s: the format defines neither a quote character nor an escape character",
                quoteForMessage(value));
    }

    private static Map<Long, Map<Integer, String>> groupEdits(final CellEdit... edits) {
        final Map<Long, Map<Integer, String>> byRecord = new LinkedHashMap<>();
        if (edits != null) {
            for (final CellEdit edit : edits) {
                Objects.requireNonNull(edit, "edit");
                byRecord.computeIfAbsent(edit.record, k -> new LinkedHashMap<>()).put(edit.column, edit.value);
            }
        }
        return byRecord;
    }

    private static Map<Long, Map<Integer, String>> groupEdits(final Iterable<CellEdit> edits) {
        final Map<Long, Map<Integer, String>> byRecord = new LinkedHashMap<>();
        for (final CellEdit edit : edits) {
            Objects.requireNonNull(edit, "edit");
            byRecord.computeIfAbsent(edit.record, k -> new LinkedHashMap<>()).put(edit.column, edit.value);
        }
        return byRecord;
    }

    /**
     * Parses the text with a {@link Lexer} and records the raw character span of every field, mirroring
     * {@link CSVParser#nextRecord()} including comment attachment and trailing-delimiter handling.
     */
    private static List<int[]> locateSpans(final String csvText, final CSVFormat format,
            final Map<Long, Map<Integer, String>> byRecord) throws CSVException {
        final List<int[]> records = new ArrayList<>();
        try (Lexer lexer = new Lexer(format, new ExtendedBufferedReader(new StringReader(csvText)))) {
            final Token token = new Token();
            long recordIndex = -1;
            List<int[]> fieldSpans = null;
            while (true) {
                token.reset();
                lexer.nextToken(token);
                switch (token.type) {
                case TOKEN:
                case EORECORD:
                    if (fieldSpans == null) {
                        fieldSpans = new ArrayList<>();
                        recordIndex++;
                    }
                    addFieldSpan(fieldSpans, token, format, token.type == Token.Type.EORECORD);
                    if (token.type == Token.Type.EORECORD) {
                        finalizeRecord(records, fieldSpans, recordIndex);
                        fieldSpans = null;
                    }
                    break;
                case EOF:
                    if (token.isReady) {
                        if (fieldSpans == null) {
                            fieldSpans = new ArrayList<>();
                            recordIndex++;
                        }
                        addFieldSpan(fieldSpans, token, format, true);
                        finalizeRecord(records, fieldSpans, recordIndex);
                    }
                    validateTargets(records, byRecord);
                    return records;
                case COMMENT:
                    // Comments attach to the following record but never occupy a column.
                    break;
                default:
                    throw new CSVException("Unexpected token type while editing: %s", token.type);
                }
            }
        } catch (final CSVException e) {
            throw e;
        } catch (final IOException e) {
            // CSVException only offers a message constructor; attach the cause explicitly.
            final CSVException exception = new CSVException("Failed to parse CSV text for editing: %s", e.getMessage());
            exception.initCause(e);
            throw exception;
        }
    }

    private static void addFieldSpan(final List<int[]> fieldSpans, final Token token, final CSVFormat format,
            final boolean lastInRecord) {
        final String value = token.content.toString();
        if (lastInRecord && format.trim(value).isEmpty() && format.getTrailingDelimiter() && !token.isQuoted) {
            // Mirror CSVParser: the empty value produced by a trailing delimiter is dropped.
            return;
        }
        fieldSpans.add(new int[] { Math.toIntExact(token.startCharPosition), Math.toIntExact(token.endCharPosition) });
    }

    private static void finalizeRecord(final List<int[]> records, final List<int[]> fieldSpans,
            final long recordIndex) {
        while (records.size() <= recordIndex) {
            records.add(null);
        }
        final int[] flat = new int[fieldSpans.size() * 2];
        for (int i = 0; i < fieldSpans.size(); i++) {
            flat[2 * i] = fieldSpans.get(i)[0];
            flat[2 * i + 1] = fieldSpans.get(i)[1];
        }
        records.set((int) recordIndex, flat);
    }

    private static boolean needsQuoting(final String value, final CSVFormat format) {
        if (value.isEmpty()) {
            // The empty replacement always parses as the empty value because the surrounding delimiter or record
            // boundary is preserved.
            return false;
        }
        final String delimiter = format.getDelimiterString();
        final Character commentStart = format.getCommentMarker();
        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            if (c == '\r' || c == '\n' || delimiter.indexOf(c) >= 0) {
                return true;
            }
            if (i == 0 && commentStart != null && c == commentStart.charValue()) {
                return true;
            }
        }
        final Character quote = format.getQuoteCharacter();
        if (quote != null && value.indexOf(quote.charValue()) >= 0) {
            return true;
        }
        final Character escape = format.getEscapeCharacter();
        if (escape != null && value.indexOf(escape.charValue()) >= 0) {
            return true;
        }
        return false;
    }

    private static String quoteForMessage(final String value) {
        final String text = String.valueOf(value);
        if (text.length() <= 32) {
            return "<" + text + ">";
        }
        return "<" + text.substring(0, 32) + "...>";
    }

    private static String quoteValue(final String value, final char quote, final Character escape) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        out.append(quote);
        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            if (c == quote) {
                out.append(quote).append(quote);
            } else if (escape != null && c == escape.charValue()) {
                // Inside quotes the escape character only keeps its meaning when doubled.
                out.append(escape).append(escape);
            } else {
                out.append(c);
            }
        }
        out.append(quote);
        return out.toString();
    }

    private static void validateTargets(final List<int[]> records,
            final Map<Long, Map<Integer, String>> byRecord) throws CSVException {
        for (final Long record : byRecord.keySet()) {
            if (record >= records.size() || records.get(record.intValue()) == null) {
                throw new CSVException("Record %,d does not exist; the input contains %,d record(s)",
                        record, records.size());
            }
        }
    }
}
