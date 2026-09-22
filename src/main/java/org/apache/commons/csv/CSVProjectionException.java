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

/**
 * Thrown when a {@link CSVProjection} cannot be compiled or applied.
 *
 * <p>
 * The exception carries the original record number (or {@code -1} when the failure happens at compile time, before any
 * record is involved), the original column (header) name, and the target alias of the projected column that caused the
 * failure.
 * </p>
 *
 * @since 1.15.0
 */
public class CSVProjectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The original record number, or {@code -1} when not applicable (compile-time failures). */
    private final long recordNumber;

    /** The original column (header) name. */
    private final String columnName;

    /** The target alias of the projected column. */
    private final String alias;

    /**
     * Constructs a new exception.
     *
     * @param recordNumber the original record number, or {@code -1} when not applicable.
     * @param columnName the original column (header) name.
     * @param alias the target alias.
     * @param message the detail message.
     */
    public CSVProjectionException(final long recordNumber, final String columnName, final String alias, final String message) {
        super(message);
        this.recordNumber = recordNumber;
        this.columnName = columnName;
        this.alias = alias;
    }

    /**
     * Gets the target alias of the projected column that caused this exception.
     *
     * @return the target alias.
     */
    public String getAlias() {
        return alias;
    }

    /**
     * Gets the original column (header) name that caused this exception.
     *
     * @return the original column name.
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * Gets the original record number, or {@code -1} when the failure is not tied to a record (compile-time failures).
     *
     * @return the original record number or {@code -1}.
     */
    public long getRecordNumber() {
        return recordNumber;
    }
}
