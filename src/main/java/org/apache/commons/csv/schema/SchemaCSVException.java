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

import org.apache.commons.csv.CSVParser;

/**
 * Signals that a schema-aware CSV stream is invalid and that the owning
 * {@link SchemaCSVReader} has entered its terminated state.
 *
 * <p>
 * The exception carries the {@linkplain #getSectionNumber() section number} and the
 * {@linkplain #getRecordNumber() record number} at which the failure was detected, so the
 * offending position in the stream can be located. Section numbers are 1-based; a value of
 * {@code 0} means the failure occurred before the first section was declared. Record
 * numbers are the underlying {@link CSVParser#getRecordNumber() parser record numbers},
 * which are 1-based and count every record in the stream, including schema declarations.
 * </p>
 *
 * @see SchemaCSVReader
 * @since 1.0
 */
public class SchemaCSVException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Section number (1-based); 0 means before the first section. */
    private final int sectionNumber;

    /** Record number as reported by the underlying parser (1-based). */
    private final long recordNumber;

    /**
     * Constructs a new instance.
     *
     * @param message the detail message.
     * @param sectionNumber the section number (1-based), or 0 if before the first section.
     * @param recordNumber the record number from the underlying parser (1-based).
     */
    public SchemaCSVException(final String message, final int sectionNumber, final long recordNumber) {
        super(message);
        this.sectionNumber = sectionNumber;
        this.recordNumber = recordNumber;
    }

    /**
     * Gets the section number at which the failure was detected.
     *
     * @return the section number, 1-based; {@code 0} if the failure occurred before the
     *         first section was declared.
     */
    public int getSectionNumber() {
        return sectionNumber;
    }

    /**
     * Gets the record number at which the failure was detected.
     * <p>
     * This is the record number reported by the underlying {@link CSVParser}, which is
     * 1-based and counts every record in the stream, including schema declarations.
     * </p>
     *
     * @return the record number, 1-based.
     */
    public long getRecordNumber() {
        return recordNumber;
    }
}
