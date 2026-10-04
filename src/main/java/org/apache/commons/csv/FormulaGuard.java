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

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A field converter that guards CSV fields against formula injection before they are handed to spreadsheet software.
 *
 * <p>
 * The guard is a {@link Function} from {@code Object} to {@code Object}. It is applied to every field printed by a {@link CSVPrinter} when
 * {@link FormulaProtection#ON} is set on the format, and it can also be used directly on a stream of values:
 * </p>
 *
 * <pre>
 * CSVFormat format = CSVFormat.EXCEL.builder().setFormulaProtection(FormulaProtection.ON).get();
 * try (CSVPrinter printer = format.printer()) {
 *     // The printer applies the guard automatically.
 *     printer.printRecord("=1+1", FormulaGuard.numeric("-12.5"));
 *     // Or apply it yourself to any stream of values.
 *     printer.printRecord(Stream.of("=1+1", "@cmd").map(FormulaGuard.INSTANCE));
 * }
 * </pre>
 *
 * <p>
 * The conversion rules are:
 * </p>
 * <ul>
 * <li>{@link CharSequence} values are treated as text. Leading whitespace is ignored; if the first remaining character is one of the formula introducers
 * {@code =}, {@code +}, {@code -}, or {@code @}, the value is prefixed with a single quote {@code '}. The original value is never modified; the prefixed
 * copy is what gets printed. An empty string and a value that already starts with a single quote are passed through unchanged.</li>
 * <li>{@link Number} values (for example {@link Integer} or {@link Double}) are passed through unchanged; they cannot carry a formula.</li>
 * <li>Values wrapped in {@link Numeric} through {@link #numeric(CharSequence)} are declared as numbers by the caller. They are validated against a strict
 * number syntax (an optional sign, digits with an optional decimal point, and an optional exponent). Legal values such as {@code -12.5} pass through
 * unchanged; anything that is not a strict number, for example {@code 1+2}, is rejected with an {@link IllegalArgumentException}. The declaration is never
 * trusted on its own.</li>
 * <li>All other values are passed through unchanged.</li>
 * </ul>
 *
 * <p>
 * The single-quote prefix is added before the normal CSV formatting, so the original {@link CSVFormat} still decides how the result is quoted and escaped.
 * CSV double quotes are not a protection measure.
 * </p>
 *
 * <p>
 * Example output for {@code CSVFormat.EXCEL} with protection enabled:
 * </p>
 *
 * <pre>
 * '=1+1,-12.5,'@cmd
 * </pre>
 *
 * @see FormulaProtection
 * @since 1.15.0
 */
public final class FormulaGuard implements Function<Object, Object> {

    /** The singleton instance. */
    public static final FormulaGuard INSTANCE = new FormulaGuard();

    /**
     * A strict number syntax: an optional sign, then digits with an optional decimal point (or a leading decimal point), then an optional exponent.
     */
    private static final Pattern STRICT_NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    /** The formula introducers recognized after leading whitespace is ignored. */
    private static final String FORMULA_INTRODUCERS = "=+-@";

    /**
     * No instances besides the singleton.
     */
    private FormulaGuard() {
        // noop
    }

    /**
     * Wraps a textual value as a number so that it is validated against a strict number syntax when printed.
     *
     * @param value the value declared as a number, must not be {@code null}
     * @return a wrapper that carries the number declaration
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static Numeric numeric(final CharSequence value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return new Numeric(value);
    }

    /**
     * Converts the given field value.
     *
     * @param value the field value to convert, may be {@code null}
     * @return the converted value
     * @throws IllegalArgumentException if a value declared as a number is not a strict number
     */
    @Override
    public Object apply(final Object value) {
        if (value instanceof Numeric) {
            return validateNumeric((Numeric) value);
        }
        if (value instanceof CharSequence) {
            return protectText((CharSequence) value);
        }
        return value;
    }

    /**
     * Guards a text value. Leading whitespace is ignored; if the first remaining character is a formula introducer, the value is prefixed with a single
     * quote.
     *
     * @param value the text value, must not be {@code null}
     * @return the original value, or a prefixed copy if it is dangerous
     */
    public static CharSequence protectText(final CharSequence value) {
        int i = 0;
        final int len = value.length();
        while (i < len && isWhitespace(value.charAt(i))) {
            i++;
        }
        if (i < len) {
            final char c = value.charAt(i);
            if (FORMULA_INTRODUCERS.indexOf(c) >= 0) {
                return "'" + value;
            }
        }
        return value;
    }

    /**
     * Validates that a value declared as a number is a strict number.
     *
     * @param numeric the wrapped number declaration, must not be {@code null}
     * @return the original value if it is a strict number
     * @throws IllegalArgumentException if the value is not a strict number
     */
    public static String validateNumeric(final Numeric numeric) {
        final String s = numeric.getValue().toString();
        if (!STRICT_NUMBER.matcher(s).matches()) {
            throw new IllegalArgumentException("Not a strict number: " + s);
        }
        return s;
    }

    private static boolean isWhitespace(final char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * A value declared as a number. The declaration is never trusted on its own; the wrapped value is validated against a strict number syntax when printed.
     */
    public static final class Numeric {

        private final CharSequence value;

        private Numeric(final CharSequence value) {
            this.value = value;
        }

        /**
         * Gets the wrapped value.
         *
         * @return the wrapped value
         */
        public CharSequence getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }
}
