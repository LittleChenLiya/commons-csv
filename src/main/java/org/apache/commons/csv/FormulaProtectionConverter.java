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
import java.util.regex.Pattern;

/**
 * An opt-in field converter that protects CSV exports against spreadsheet formula injection
 * (also known as "CSV injection").
 *
 * <p>
 * Spreadsheet applications (Excel, LibreOffice Calc, Google Sheets, ...) interpret cell text whose first
 * non-whitespace character is {@code =}, {@code +}, {@code -} or {@code @} as a formula. When a CSV file
 * containing such values is opened, the formula may execute. This converter mitigates the risk by prefixing
 * dangerous <em>text</em> values with a single quote ({@code '}), which spreadsheet applications render as a
 * literal text marker instead of evaluating the cell.
 * </p>
 *
 * <p>
 * Protection strategy:
 * </p>
 * <ul>
 * <li>Only text fields are inspected. The first character after ignoring leading whitespace
 * ({@link Character#isWhitespace(char)}) is checked against {@code =}, {@code +}, {@code -} and {@code @}.</li>
 * <li>Dangerous text is prefixed with a single quote. The caller's original value object is never mutated; a new
 * String is produced for the output only.</li>
 * <li>Values the caller explicitly declares as numeric via {@link #convertNumber(String)} (or
 * {@link #printNumber(CSVPrinter, String)}) must match a strict decimal number grammar. The declaration is never
 * trusted blindly: a non-numeric value such as {@code "1+2"} is rejected with an
 * {@link IllegalArgumentException}. Legal negative numbers like {@code "-12.5"} are valid numbers and pass
 * through unchanged.</li>
 * <li>CSV double quotes are <em>not</em> used as an anti-formula measure. After conversion the value is handed to
 * {@link CSVPrinter#print(Object)}, so the {@link CSVFormat} configured on the printer performs CSV escaping
 * exactly as before.</li>
 * </ul>
 *
 * <p>
 * This converter is never applied implicitly: existing {@link CSVPrinter} behavior is unchanged unless the
 * caller explicitly routes values through this class.
 * </p>
 *
 * @since 1.15.0
 */
public class FormulaProtectionConverter {

    /**
     * Characters that trigger formula evaluation when they are the first non-whitespace character of a cell.
     */
    private static final String DANGEROUS_CHARS = "=+-@";

    /**
     * Prefix prepended to dangerous text values. Spreadsheet applications treat a leading single quote as a
     * "literal text" marker and do not evaluate the rest of the cell.
     */
    private static final char PROTECTION_PREFIX = '\'';

    /**
     * Strict decimal number grammar: an optional sign, an integer and/or fraction part, and an optional decimal
     * exponent. Examples: {@code 0}, {@code -12.5}, {@code +3}, {@code .5}, {@code 12.}, {@code 1e5},
     * {@code -1.5E-3}. Rejects (among others) empty strings, surrounding whitespace, thousands separators,
     * hex/octal literals, {@code NaN}, {@code Infinity} and expressions such as {@code 1+2}.
     */
    private static final Pattern STRICT_NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /**
     * Converts a text value for safe spreadsheet export.
     *
     * <p>
     * If the first non-whitespace character of the value is {@code =}, {@code +}, {@code -} or {@code @}, a
     * single quote is prepended to the <em>original</em> value (leading whitespace is preserved). Otherwise the
     * value is returned unchanged. Empty and whitespace-only values are returned unchanged.
     * </p>
     *
     * @param value the text value, may be null.
     * @return the protected value, or null if {@code value} is null.
     */
    public String convertText(final String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int i = 0;
        final int len = value.length();
        while (i < len && Character.isWhitespace(value.charAt(i))) {
            i++;
        }
        if (i < len && DANGEROUS_CHARS.indexOf(value.charAt(i)) >= 0) {
            return PROTECTION_PREFIX + value;
        }
        return value;
    }

    /**
     * Converts a value the caller explicitly declares as numeric.
     *
     * <p>
     * The declaration is verified, not trusted: the value must fully match a strict decimal number grammar
     * (optional sign, digits with optional fraction, optional exponent). Valid numbers &mdash; including legal
     * negative numbers such as {@code "-12.5"} &mdash; are returned unchanged because spreadsheet applications
     * parse them as numbers, not formulas. Anything else (for example {@code "1+2"}, {@code ""}, {@code " 1"},
     * {@code "1,000"}, {@code "NaN"}) is rejected.
     * </p>
     *
     * @param value the declared numeric value, may not be null.
     * @return the value unchanged.
     * @throws IllegalArgumentException if the value does not match the strict numeric grammar.
     */
    public String convertNumber(final String value) {
        if (value == null || !STRICT_NUMBER.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a strict decimal number: " + value);
        }
        return value;
    }

    /**
     * Converts an arbitrary value for safe spreadsheet export.
     *
     * <ul>
     * <li>null is returned as null (the printer renders it as an empty field or the format's null string).</li>
     * <li>{@link Number} instances are emitted via {@code toString()} when the result matches the strict numeric
     * grammar; non-finite numbers ({@code NaN}, {@code Infinity}) fall back to text protection.</li>
     * <li>Everything else is converted with {@link String#valueOf(Object)} and protected as text.</li>
     * </ul>
     *
     * @param value the value, may be null.
     * @return the protected value, or null if {@code value} is null.
     */
    public Object convert(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            final String s = value.toString();
            return STRICT_NUMBER.matcher(s).matches() ? s : convertText(s);
        }
        return convertText(String.valueOf(value));
    }

    /**
     * Converts {@code value} and prints it with the given printer, which applies its configured
     * {@link CSVFormat} escaping to the converted value.
     *
     * @param printer the target printer.
     * @param value the value, may be null.
     * @throws IOException if an I/O error occurs.
     */
    public void print(final CSVPrinter printer, final Object value) throws IOException {
        printer.print(convert(value));
    }

    /**
     * Validates {@code value} as a strict decimal number and prints it with the given printer.
     *
     * @param printer the target printer.
     * @param value the declared numeric value.
     * @throws IllegalArgumentException if the value is not a strict decimal number.
     * @throws IOException if an I/O error occurs.
     */
    public void printNumber(final CSVPrinter printer, final String value) throws IOException {
        printer.print(convertNumber(value));
    }

    /**
     * Converts each value and prints a complete record with the given printer.
     *
     * @param printer the target printer.
     * @param values the record values.
     * @throws IOException if an I/O error occurs.
     */
    public void printRecord(final CSVPrinter printer, final Iterable<?> values) throws IOException {
        for (final Object value : values) {
            print(printer, value);
        }
        printer.println();
    }

    /**
     * Converts each value and prints a complete record with the given printer.
     *
     * @param printer the target printer.
     * @param values the record values.
     * @throws IOException if an I/O error occurs.
     */
    public void printRecord(final CSVPrinter printer, final Object... values) throws IOException {
        for (final Object value : values) {
            print(printer, value);
        }
        printer.println();
    }
}
