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
 * Thrown when a {@link CSVProjection} cannot produce a value for a projected column of a specific {@link CSVRecord}.
 *
 * <p>
 * The exception carries the originating record number, the source column (header) name and the target output alias so
 * that callers can report or handle failures without re-parsing the input.
 * </p>
 *
 * @since 1.15.0
 */
public class CSVProjectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The number of the record being projected when the failure occurred. */
    private final long recordNumber;

    /** The source column (header) name that failed to project. */
    private final String columnName;

    /** The target output alias of the column that failed to project. */
    private final String alias;

    /**
     * Constructs a new exception.
     *
     * @param recordNumber the number of the record being projected.
     * @param columnName the source column (header) name.
     * @param alias the target output alias.
     * @param message the detail message.
     */
    public CSVProjectionException(final long recordNumber, final String columnName, final String alias, final String message) {
        super(message);
        this.recordNumber = recordNumber;
        this.columnName = columnName;
        this.alias = alias;
    }

    /**
     * Gets the target output alias of the column that failed to project.
     *
     * @return the target output alias.
     */
    public String getAlias() {
        return alias;
    }

    /**
     * Gets the source column (header) name that failed to project.
     *
     * @return the source column name.
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * Gets the number of the record being projected when the failure occurred.
     *
     * @return the record number.
     */
    public long getRecordNumber() {
        return recordNumber;
    }
}
