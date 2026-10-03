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

import java.io.IOException;

/**
 * Signals that a CSV stream read through a {@link SchemaCSVReader} violates the
 * schema-section contract.
 *
 * <p>The exception carries the kind of violation ({@link #getError()}), the
 * 1-based section number ({@link #getSection()}) and the 1-based physical CSV
 * record number ({@link #getRecordNumber()}) so callers can locate the problem.
 * A {@link SchemaCSVError#TERMINATED TERMINATED} exception is thrown by every
 * further read once the reader is in its terminal state; it has the original
 * failure as its {@link #getCause() cause}.</p>
 *
 * @since 1.15.0
 */
public class SchemaCSVException extends IOException {

    private static final long serialVersionUID = 1L;

    private final SchemaCSVError error;

    private final long section;

    private final long recordNumber;

    /**
     * Constructs a new instance.
     *
     * @param error the kind of violation.
     * @param section the 1-based section number.
     * @param recordNumber the 1-based physical CSV record number.
     * @param message the detail message.
     */
    SchemaCSVException(final SchemaCSVError error, final long section, final long recordNumber, final String message) {
        this(error, section, recordNumber, message, null);
    }

    /**
     * Constructs a new instance with a cause.
     *
     * @param error the kind of violation.
     * @param section the 1-based section number.
     * @param recordNumber the 1-based physical CSV record number.
     * @param message the detail message.
     * @param cause the original failure, or {@code null}.
     */
    SchemaCSVException(final SchemaCSVError error, final long section, final long recordNumber, final String message,
            final Throwable cause) {
        super(message, cause);
        this.error = error;
        this.section = section;
        this.recordNumber = recordNumber;
    }

    /**
     * Gets the kind of violation.
     *
     * @return the error kind, never {@code null}.
     */
    public SchemaCSVError getError() {
        return error;
    }

    /**
     * Gets the 1-based section number the violation belongs to.
     *
     * @return the section number.
     */
    public long getSection() {
        return section;
    }

    /**
     * Gets the 1-based physical CSV record number of the offending record.
     *
     * @return the record number in the underlying CSV stream.
     */
    public long getRecordNumber() {
        return recordNumber;
    }
}
