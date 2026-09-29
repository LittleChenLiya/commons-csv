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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Declares a typed validation rule for a single CSV column, referenced either by zero-based column position or by a
 * unique header name.
 *
 * <p>
 * Rules are explicit: the validator never guesses a column type. Supported types are {@link Type#INTEGER} (parsed to
 * {@link Long}), {@link Type#DECIMAL} (parsed to {@link java.math.BigDecimal} without ever going through a
 * floating-point type), {@link Type#DATE} (parsed to {@link LocalDate} with a locale-independent formatter) and
 * {@link Type#ENUM} (matched against an allowed set of values or a Java enum class).
 * </p>
 *
 * <p>
 * By default a column is required: an empty string, a null token (see
 * {@link CSVFormat.Builder#setNullString(String)}) or a missing column each produce a distinct
 * {@link ValidationError}. Calling {@link #nullable()} allows empty strings and null tokens, which then yield a
 * {@code null} typed value instead of an error; a structurally missing column is always reported.
 * </p>
 *
 * @since 1.15.0
 */
public final class ColumnRule {

    /**
     * The kind of typed value a rule produces.
     */
    public enum Type {
        /** Parses to {@link Long}. */
        INTEGER,
        /** Parses to {@link java.math.BigDecimal}, preserving the exact decimal representation. */
        DECIMAL,
        /** Parses to {@link LocalDate}. */
        DATE,
        /** Matches an allowed set of strings or the constants of a Java enum class. */
        ENUM
    }

    /**
     * Creates a required date rule using the locale-independent ISO-8601 format {@code uuuu-MM-dd}.
     *
     * @param columnIndex zero-based column position.
     * @return a new rule.
     */
    public static ColumnRule date(final int columnIndex) {
        return date(columnIndex, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Creates a required date rule using the given pattern, resolved with {@link Locale#ROOT} so parsing does not
     * depend on the JVM default locale.
     *
     * @param columnIndex zero-based column position.
     * @param pattern a {@link DateTimeFormatter} pattern.
     * @return a new rule.
     */
    public static ColumnRule date(final int columnIndex, final String pattern) {
        return date(columnIndex, DateTimeFormatter.ofPattern(Objects.requireNonNull(pattern, "pattern"), Locale.ROOT));
    }

    /**
     * Creates a required date rule using the locale-independent ISO-8601 format {@code uuuu-MM-dd}.
     *
     * @param header unique header name.
     * @return a new rule.
     */
    public static ColumnRule date(final String header) {
        return date(header, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Creates a required date rule using the given pattern, resolved with {@link Locale#ROOT} so parsing does not
     * depend on the JVM default locale.
     *
     * @param header unique header name.
     * @param pattern a {@link DateTimeFormatter} pattern.
     * @return a new rule.
     */
    public static ColumnRule date(final String header, final String pattern) {
        return date(header, DateTimeFormatter.ofPattern(Objects.requireNonNull(pattern, "pattern"), Locale.ROOT));
    }

    private static ColumnRule date(final int columnIndex, final DateTimeFormatter formatter) {
        return new ColumnRule(Type.DATE, columnIndex, null, formatter, null, null);
    }

    private static ColumnRule date(final String header, final DateTimeFormatter formatter) {
        return new ColumnRule(Type.DATE, -1, header, formatter, null, null);
    }

    /**
     * Creates a required decimal rule. Values are parsed with {@link java.math.BigDecimal#BigDecimal(String)}, so the
     * exact decimal representation is preserved and never routed through {@code double}.
     *
     * @param columnIndex zero-based column position.
     * @return a new rule.
     */
    public static ColumnRule decimal(final int columnIndex) {
        return new ColumnRule(Type.DECIMAL, columnIndex, null, null, null, null);
    }

    /**
     * Creates a required decimal rule. Values are parsed with {@link java.math.BigDecimal#BigDecimal(String)}, so the
     * exact decimal representation is preserved and never routed through {@code double}.
     *
     * @param header unique header name.
     * @return a new rule.
     */
    public static ColumnRule decimal(final String header) {
        return new ColumnRule(Type.DECIMAL, -1, header, null, null, null);
    }

    /**
     * Creates a required enum rule whose value must equal one of the given allowed strings (case-sensitive).
     *
     * @param columnIndex zero-based column position.
     * @param allowedValues non-empty set of allowed values.
     * @return a new rule.
     */
    public static ColumnRule enumeration(final int columnIndex, final String... allowedValues) {
        return new ColumnRule(Type.ENUM, columnIndex, null, null, toSet(allowedValues), null);
    }

    /**
     * Creates a required enum rule whose value must match the {@link Enum#name() name} of a constant of the given
     * enum class (case-sensitive). The typed value is the matching enum constant.
     *
     * @param columnIndex zero-based column position.
     * @param enumClass the enum class.
     * @param <E> the enum type.
     * @return a new rule.
     */
    public static <E extends Enum<E>> ColumnRule enumeration(final int columnIndex, final Class<E> enumClass) {
        return new ColumnRule(Type.ENUM, columnIndex, null, null, enumNames(enumClass), enumClass);
    }

    /**
     * Creates a required enum rule whose value must equal one of the given allowed strings (case-sensitive).
     *
     * @param header unique header name.
     * @param allowedValues non-empty set of allowed values.
     * @return a new rule.
     */
    public static ColumnRule enumeration(final String header, final String... allowedValues) {
        return new ColumnRule(Type.ENUM, -1, header, null, toSet(allowedValues), null);
    }

    /**
     * Creates a required enum rule whose value must match the {@link Enum#name() name} of a constant of the given
     * enum class (case-sensitive). The typed value is the matching enum constant.
     *
     * @param header unique header name.
     * @param enumClass the enum class.
     * @param <E> the enum type.
     * @return a new rule.
     */
    public static <E extends Enum<E>> ColumnRule enumeration(final String header, final Class<E> enumClass) {
        return new ColumnRule(Type.ENUM, -1, header, null, enumNames(enumClass), enumClass);
    }

    private static Set<String> enumNames(final Class<? extends Enum<?>> enumClass) {
        Objects.requireNonNull(enumClass, "enumClass");
        final Enum<?>[] constants = enumClass.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalArgumentException("enumClass has no constants: " + enumClass);
        }
        final Set<String> names = new LinkedHashSet<>();
        for (final Enum<?> constant : constants) {
            names.add(constant.name());
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Creates a required integer rule. Values are parsed with {@link Long#parseLong(String)}.
     *
     * @param columnIndex zero-based column position.
     * @return a new rule.
     */
    public static ColumnRule integer(final int columnIndex) {
        return new ColumnRule(Type.INTEGER, columnIndex, null, null, null, null);
    }

    /**
     * Creates a required integer rule. Values are parsed with {@link Long#parseLong(String)}.
     *
     * @param header unique header name.
     * @return a new rule.
     */
    public static ColumnRule integer(final String header) {
        return new ColumnRule(Type.INTEGER, -1, header, null, null, null);
    }

    private static Set<String> toSet(final String... allowedValues) {
        Objects.requireNonNull(allowedValues, "allowedValues");
        if (allowedValues.length == 0) {
            throw new IllegalArgumentException("allowedValues must not be empty");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(allowedValues)));
    }

    private final Type type;
    private final int columnIndex;
    private final String header;
    private final DateTimeFormatter dateFormatter;
    private final Set<String> allowedValues;
    private final Class<? extends Enum<?>> enumClass;
    private final boolean nullable;

    private ColumnRule(final Type type, final int columnIndex, final String header, final DateTimeFormatter dateFormatter,
            final Set<String> allowedValues, final Class<? extends Enum<?>> enumClass) {
        this(type, columnIndex, header, dateFormatter, allowedValues, enumClass, false);
    }

    private ColumnRule(final Type type, final int columnIndex, final String header, final DateTimeFormatter dateFormatter,
            final Set<String> allowedValues, final Class<? extends Enum<?>> enumClass, final boolean nullable) {
        this.type = Objects.requireNonNull(type, "type");
        this.columnIndex = columnIndex;
        this.header = header;
        this.dateFormatter = dateFormatter;
        this.allowedValues = allowedValues;
        this.enumClass = enumClass;
        this.nullable = nullable;
        if (columnIndex < 0 && header == null) {
            throw new IllegalArgumentException("A rule needs a column index or a header name");
        }
        if (columnIndex >= 0 && header != null) {
            throw new IllegalArgumentException("A rule takes either a column index or a header name, not both");
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ColumnRule)) {
            return false;
        }
        final ColumnRule other = (ColumnRule) obj;
        return type == other.type && columnIndex == other.columnIndex && nullable == other.nullable
                && Objects.equals(header, other.header) && Objects.equals(dateFormatter, other.dateFormatter)
                && Objects.equals(allowedValues, other.allowedValues) && Objects.equals(enumClass, other.enumClass);
    }

    /**
     * Gets the allowed values for an {@link Type#ENUM} rule, or {@code null} for other types.
     *
     * @return the allowed values, or {@code null}.
     */
    public Set<String> getAllowedValues() {
        return allowedValues;
    }

    /**
     * Gets the zero-based column position, or {@code -1} when this rule references a header name.
     *
     * @return the column index, or {@code -1}.
     */
    public int getColumnIndex() {
        return columnIndex;
    }

    /**
     * Gets the formatter used by a {@link Type#DATE} rule, or {@code null} for other types.
     *
     * @return the date formatter, or {@code null}.
     */
    public DateTimeFormatter getDateFormatter() {
        return dateFormatter;
    }

    /**
     * Gets the enum class of an {@link Type#ENUM} rule created with {@link #enumeration(int, Class)}, or {@code null}.
     *
     * @return the enum class, or {@code null}.
     */
    public Class<? extends Enum<?>> getEnumClass() {
        return enumClass;
    }

    /**
     * Gets the header name this rule references, or {@code null} when it references a column position.
     *
     * @return the header name, or {@code null}.
     */
    public String getHeader() {
        return header;
    }

    /**
     * Gets the rule type.
     *
     * @return the rule type.
     */
    public Type getType() {
        return type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, Integer.valueOf(columnIndex), header, dateFormatter, allowedValues, enumClass,
                Boolean.valueOf(nullable));
    }

    /**
     * Tests whether empty strings and null tokens are accepted and produce a {@code null} typed value.
     *
     * @return whether this rule is nullable.
     */
    public boolean isNullable() {
        return nullable;
    }

    /**
     * Returns a copy of this rule that accepts empty strings and null tokens, yielding a {@code null} typed value for
     * them instead of an error.
     *
     * @return a nullable copy of this rule.
     */
    public ColumnRule nullable() {
        return new ColumnRule(type, columnIndex, header, dateFormatter, allowedValues, enumClass, true);
    }

    @Override
    public String toString() {
        final String ref = header != null ? "header=" + header : "columnIndex=" + columnIndex;
        return "ColumnRule[" + type + ", " + ref + (nullable ? ", nullable" : "") + "]";
    }
}
