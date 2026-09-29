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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.commons.csv.ValidationError.Kind;

/**
 * Validates CSV records against explicit, caller-declared {@link ColumnRule}s and returns typed values or structured
 * errors, row by row.
 *
 * <p>
 * The validator never guesses column types: every validated column is declared up front as integer, decimal, date or
 * enum, referenced either by zero-based column position or by a unique header name. Decimals are parsed to
 * {@link BigDecimal} directly, so values such as {@code 9007199254740993.01} are preserved exactly and never routed
 * through {@code double}. Date parsing uses {@link java.util.Locale#ROOT} and does not depend on the JVM default
 * locale.
 * </p>
 *
 * <p>
 * Empty strings, missing columns and null tokens (see {@link CSVFormat.Builder#setNullString(String)}) are three
 * distinct situations and are reported with distinct {@link ValidationError.Kind}s. All errors of a record are
 * aggregated into a single {@link ValidatedRecord} that keeps the record number and the physical start line.
 * Validation continues with the following records until the configured maximum number of errors is reached; the
 * result then reports {@link ValidationResult#isTruncated()}.
 * </p>
 *
 * <p>
 * The validator only reads from the {@link CSVParser} it is given; it does not change the parser's configuration or
 * any parsing behavior.
 * </p>
 *
 * <p>
 * Example:
 * </p>
 *
 * <pre>
 * CsvValidator validator = CsvValidator.builder()
 *         .rule(ColumnRule.integer("id"))
 *         .rule(ColumnRule.decimal("amount"))
 *         .rule(ColumnRule.date("when"))
 *         .rule(ColumnRule.enumeration("status", "NEW", "PAID"))
 *         .rule(ColumnRule.decimal("discount").nullable())
 *         .maxErrors(100)
 *         .build();
 * ValidationResult result = validator.validate(parser);
 * </pre>
 *
 * @since 1.15.0
 */
public final class CsvValidator {

    /**
     * Builds {@link CsvValidator} instances.
     */
    public static final class Builder {

        private final List<ColumnRule> rules = new ArrayList<>();
        private int maxErrors = DEFAULT_MAX_ERRORS;

        private Builder() {
        }

        /**
         * Sets the maximum number of errors to collect before validation stops and the result is marked
         * {@link ValidationResult#isTruncated() truncated}. Defaults to {@link #DEFAULT_MAX_ERRORS}.
         *
         * @param maxErrors the maximum error count, must be at least 1.
         * @return this builder.
         */
        public Builder maxErrors(final int maxErrors) {
            if (maxErrors < 1) {
                throw new IllegalArgumentException("maxErrors must be at least 1: " + maxErrors);
            }
            this.maxErrors = maxErrors;
            return this;
        }

        /**
         * Adds a column rule. Rules are evaluated in the order they are added.
         *
         * @param rule the rule to add.
         * @return this builder.
         */
        public Builder rule(final ColumnRule rule) {
            rules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /**
         * Adds column rules in iteration order.
         *
         * @param rules the rules to add.
         * @return this builder.
         */
        public Builder rules(final Iterable<ColumnRule> rules) {
            for (final ColumnRule rule : rules) {
                rule(rule);
            }
            return this;
        }

        /**
         * Builds the validator.
         *
         * @return a new validator.
         */
        public CsvValidator build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("At least one column rule is required");
            }
            return new CsvValidator(rules, maxErrors);
        }
    }

    /**
     * The default maximum number of errors collected before validation stops: {@value}.
     */
    public static final int DEFAULT_MAX_ERRORS = 100;

    /**
     * Creates a new builder.
     *
     * @return a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a validator with the given rules and the {@link #DEFAULT_MAX_ERRORS default} error limit.
     *
     * @param rules the column rules, at least one.
     * @return a new validator.
     */
    public static CsvValidator of(final ColumnRule... rules) {
        final Builder builder = builder();
        for (final ColumnRule rule : rules) {
            builder.rule(rule);
        }
        return builder.build();
    }

    private final List<ColumnRule> rules;
    private final int maxErrors;

    private CsvValidator(final List<ColumnRule> rules, final int maxErrors) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.maxErrors = maxErrors;
    }

    /**
     * Gets the maximum number of errors collected before validation stops.
     *
     * @return the maximum error count.
     */
    public int getMaxErrors() {
        return maxErrors;
    }

    /**
     * Gets the column rules, in evaluation order.
     *
     * @return the column rules.
     */
    public List<ColumnRule> getRules() {
        return rules;
    }

    /**
     * Validates every record of the given parser against the declared rules.
     *
     * <p>
     * Records are read until the input is exhausted or the configured maximum number of errors has been collected,
     * whichever comes first. The parser is not closed.
     * </p>
     *
     * @param parser the parser to read records from.
     * @return the aggregated validation result.
     * @throws IOException on I/O errors from the underlying reader.
     */
    public ValidationResult validate(final CSVParser parser) throws IOException {
        Objects.requireNonNull(parser, "parser");
        final int[] columnIndexes = resolveColumns(parser);
        final List<ValidatedRecord> records = new ArrayList<>();
        int errorCount = 0;
        boolean truncated = false;
        while (errorCount < maxErrors) {
            // The previous record's terminating EOL (and the header line) have already been consumed, so the
            // lexer's current line is the last completed physical line; the next record starts one line later.
            final long startLine = parser.getCurrentLineNumber() + 1;
            final CSVRecord record = parser.nextRecord();
            if (record == null) {
                break;
            }
            final ValidatedRecord validated = validateRecord(record, startLine, columnIndexes);
            records.add(validated);
            errorCount += validated.getErrors().size();
            if (errorCount >= maxErrors) {
                truncated = parser.nextRecord() != null;
                break;
            }
        }
        return new ValidationResult(records, truncated);
    }

    private int[] resolveColumns(final CSVParser parser) {
        final List<String> headerNames = parser.getHeaderNames();
        final int[] indexes = new int[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            final ColumnRule rule = rules.get(i);
            if (rule.getHeader() == null) {
                indexes[i] = rule.getColumnIndex();
            } else {
                int match = -1;
                boolean ambiguous = false;
                for (int h = 0; h < headerNames.size(); h++) {
                    if (rule.getHeader().equals(headerNames.get(h))) {
                        if (match >= 0) {
                            ambiguous = true;
                            break;
                        }
                        match = h;
                    }
                }
                if (ambiguous) {
                    indexes[i] = -2;
                } else {
                    indexes[i] = match; // -1 when the header is missing
                }
            }
        }
        return indexes;
    }

    private ValidatedRecord validateRecord(final CSVRecord record, final long startLine, final int[] columnIndexes) {
        final long recordNumber = record.getRecordNumber();
        final List<Object> values = new ArrayList<>(rules.size());
        final List<ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            final ColumnRule rule = rules.get(i);
            final int columnIndex = columnIndexes[i];
            values.add(validateColumn(record, recordNumber, startLine, rule, columnIndex, errors));
        }
        return new ValidatedRecord(recordNumber, startLine, rules, values, errors);
    }

    private Object validateColumn(final CSVRecord record, final long recordNumber, final long lineNumber, final ColumnRule rule,
            final int columnIndex, final List<ValidationError> errors) {
        final String header = rule.getHeader();
        if (columnIndex == -2) {
            errors.add(new ValidationError(Kind.DUPLICATE_HEADER, recordNumber, lineNumber, -1, header, null,
                    "Header '" + header + "' occurs more than once; use a column position instead"));
            return null;
        }
        if (columnIndex < 0) {
            errors.add(new ValidationError(Kind.MISSING_HEADER, recordNumber, lineNumber, -1, header, null,
                    "Header '" + header + "' not found"));
            return null;
        }
        if (columnIndex >= record.size()) {
            errors.add(new ValidationError(Kind.MISSING_COLUMN, recordNumber, lineNumber, columnIndex, header, null,
                    "Record has " + record.size() + " column(s), no column at index " + columnIndex));
            return null;
        }
        final String raw = record.get(columnIndex);
        if (raw == null) {
            if (rule.isNullable()) {
                return null;
            }
            errors.add(new ValidationError(Kind.NULL_VALUE, recordNumber, lineNumber, columnIndex, header, null,
                    "Null token but column is not nullable"));
            return null;
        }
        if (raw.isEmpty()) {
            if (rule.isNullable()) {
                return null;
            }
            errors.add(new ValidationError(Kind.EMPTY_VALUE, recordNumber, lineNumber, columnIndex, header, raw,
                    "Empty value but column is not nullable"));
            return null;
        }
        switch (rule.getType()) {
        case INTEGER:
            try {
                return Long.valueOf(raw);
            } catch (final NumberFormatException e) {
                errors.add(new ValidationError(Kind.INVALID_INTEGER, recordNumber, lineNumber, columnIndex, header, raw,
                        "Not an integer: '" + raw + "'"));
                return null;
            }
        case DECIMAL:
            try {
                return new BigDecimal(raw);
            } catch (final NumberFormatException e) {
                errors.add(new ValidationError(Kind.INVALID_DECIMAL, recordNumber, lineNumber, columnIndex, header, raw,
                        "Not a decimal: '" + raw + "'"));
                return null;
            }
        case DATE:
            try {
                return LocalDate.parse(raw, rule.getDateFormatter());
            } catch (final DateTimeParseException e) {
                errors.add(new ValidationError(Kind.INVALID_DATE, recordNumber, lineNumber, columnIndex, header, raw,
                        "Not a date: '" + raw + "'"));
                return null;
            }
        case ENUM:
            if (rule.getAllowedValues().contains(raw)) {
                final Class<? extends Enum<?>> enumClass = rule.getEnumClass();
                if (enumClass == null) {
                    return raw;
                }
                for (final Enum<?> constant : enumClass.getEnumConstants()) {
                    if (constant.name().equals(raw)) {
                        return constant;
                    }
                }
            }
            errors.add(new ValidationError(Kind.INVALID_ENUM, recordNumber, lineNumber, columnIndex, header, raw,
                    "Not one of " + rule.getAllowedValues() + ": '" + raw + "'"));
            return null;
        default:
            throw new IllegalStateException("Unexpected rule type: " + rule.getType());
        }
    }
}
