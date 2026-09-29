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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of validating one {@link CSVRecord}: either typed values for every rule, or one or more structured
 * {@link ValidationError}s. All errors of a record are aggregated, so a single row can report several failures at
 * once.
 *
 * <p>
 * Typed values are keyed by the {@link ColumnRule} that produced them and use the following runtime types:
 * </p>
 * <ul>
 * <li>{@link ColumnRule.Type#INTEGER} maps to {@link Long}</li>
 * <li>{@link ColumnRule.Type#DECIMAL} maps to {@link BigDecimal} (exact, never routed through {@code double})</li>
 * <li>{@link ColumnRule.Type#DATE} maps to {@link LocalDate}</li>
 * <li>{@link ColumnRule.Type#ENUM} maps to the matched {@link String}, or the enum constant when the rule was created
 * with {@link ColumnRule#enumeration(int, Class)}</li>
 * </ul>
 *
 * @since 1.15.0
 */
public final class ValidatedRecord {

    private final long recordNumber;
    private final long lineNumber;
    private final List<ColumnRule> rules;
    private final List<Object> values;
    private final List<ValidationError> errors;

    ValidatedRecord(final long recordNumber, final long lineNumber, final List<ColumnRule> rules, final List<Object> values,
            final List<ValidationError> errors) {
        this.recordNumber = recordNumber;
        this.lineNumber = lineNumber;
        this.rules = rules;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    /**
     * Gets the typed value produced for the given rule, or {@code null} when the value is absent (nullable column)
     * or the column failed validation.
     *
     * @param rule the rule used to build the validator.
     * @return the typed value, or {@code null}.
     * @throws IllegalArgumentException if the rule is not part of the validator that produced this record.
     */
    public Object get(final ColumnRule rule) {
        final int index = rules.indexOf(rule);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown rule: " + rule);
        }
        return values.get(index);
    }

    /**
     * Gets the typed value of a {@link ColumnRule.Type#DECIMAL} rule.
     *
     * @param rule a decimal rule used to build the validator.
     * @return the exact decimal value, or {@code null}.
     */
    public BigDecimal getDecimal(final ColumnRule rule) {
        return (BigDecimal) get(rule);
    }

    /**
     * Gets the typed value of a {@link ColumnRule.Type#DATE} rule.
     *
     * @param rule a date rule used to build the validator.
     * @return the parsed date, or {@code null}.
     */
    public LocalDate getDate(final ColumnRule rule) {
        return (LocalDate) get(rule);
    }

    /**
     * Gets all errors aggregated for this record, in column order. Empty when the record is valid.
     *
     * @return the errors, never {@code null}.
     */
    public List<ValidationError> getErrors() {
        return errors;
    }

    /**
     * Gets the physical line number (1-based) at which this record starts in the input. This differs from
     * {@link #getRecordNumber()} whenever a previous record spans multiple physical lines, for example because of a
     * line break inside a quoted value.
     *
     * @return the physical start line of the record.
     */
    public long getLineNumber() {
        return lineNumber;
    }

    /**
     * Gets the typed value of a {@link ColumnRule.Type#INTEGER} rule.
     *
     * @param rule an integer rule used to build the validator.
     * @return the parsed value, or {@code null}.
     */
    public Long getLong(final ColumnRule rule) {
        return (Long) get(rule);
    }

    /**
     * Gets the 1-based record number, matching {@link CSVRecord#getRecordNumber()}.
     *
     * @return the record number.
     */
    public long getRecordNumber() {
        return recordNumber;
    }

    /**
     * Gets the typed values in rule order. Entries are {@code null} for absent or invalid values.
     *
     * @return the typed values in rule order.
     */
    public List<Object> getValues() {
        return values;
    }

    /**
     * Tests whether this record passed all rules.
     *
     * @return {@code true} when there are no errors.
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    @Override
    public String toString() {
        return "ValidatedRecord[record=" + recordNumber + ", line=" + lineNumber + ", valid=" + isValid()
                + (isValid() ? ", values=" + values : ", errors=" + errors) + "]";
    }
}
