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

import java.util.Objects;

/**
 * A single structured validation failure for one column of one record.
 *
 * <p>
 * Every error carries the {@link #getRecordNumber() record number} and the {@link #getLineNumber() physical line
 * number} at which the record starts. The two differ whenever a previous record spans multiple physical lines, for
 * example because of a line break inside a quoted value.
 * </p>
 *
 * @since 1.15.0
 */
public final class ValidationError {

    /**
     * The kind of validation failure.
     */
    public enum Kind {
        /** The record has fewer columns than the referenced column position. */
        MISSING_COLUMN,
        /** The value is an empty string and the rule is not nullable. Distinct from {@link #NULL_VALUE}. */
        EMPTY_VALUE,
        /** The value is the format's null token and the rule is not nullable. Distinct from {@link #EMPTY_VALUE}. */
        NULL_VALUE,
        /** The value is not a valid {@link Long}. */
        INVALID_INTEGER,
        /** The value is not a valid {@link java.math.BigDecimal}. */
        INVALID_DECIMAL,
        /** The value is not a valid date for the rule's formatter. */
        INVALID_DATE,
        /** The value is not one of the allowed enum values. */
        INVALID_ENUM,
        /** The rule references a header name that does not exist. */
        MISSING_HEADER,
        /** The rule references a header name that occurs more than once. */
        DUPLICATE_HEADER
    }

    private final Kind kind;
    private final long recordNumber;
    private final long lineNumber;
    private final int columnIndex;
    private final String header;
    private final String rawValue;
    private final String message;

    ValidationError(final Kind kind, final long recordNumber, final long lineNumber, final int columnIndex, final String header,
            final String rawValue, final String message) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.recordNumber = recordNumber;
        this.lineNumber = lineNumber;
        this.columnIndex = columnIndex;
        this.header = header;
        this.rawValue = rawValue;
        this.message = Objects.requireNonNull(message, "message");
    }

    /**
     * Gets the zero-based column position, or {@code -1} when the column could not be resolved (for example a missing
     * or duplicate header).
     *
     * @return the column index, or {@code -1}.
     */
    public int getColumnIndex() {
        return columnIndex;
    }

    /**
     * Gets the header name of the failed rule, or {@code null} when the rule references a column position.
     *
     * @return the header name, or {@code null}.
     */
    public String getHeader() {
        return header;
    }

    /**
     * Gets the kind of failure.
     *
     * @return the kind of failure.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Gets the physical line number (1-based) at which the record starts in the input.
     *
     * @return the physical start line of the record.
     */
    public long getLineNumber() {
        return lineNumber;
    }

    /**
     * Gets a human-readable description of the failure.
     *
     * @return the error message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the raw input text that failed validation, or {@code null} for a null token or a missing column.
     *
     * @return the raw value, or {@code null}.
     */
    public String getRawValue() {
        return rawValue;
    }

    /**
     * Gets the 1-based record number, matching {@link CSVRecord#getRecordNumber()}.
     *
     * @return the record number.
     */
    public long getRecordNumber() {
        return recordNumber;
    }

    @Override
    public String toString() {
        final String ref = header != null ? "header '" + header + "'" : "column " + columnIndex;
        return "ValidationError[record=" + recordNumber + ", line=" + lineNumber + ", " + ref + ", " + kind + ": " + message + "]";
    }
}
