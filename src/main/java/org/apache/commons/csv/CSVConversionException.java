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
 * Thrown when a {@link CSVConverter} fails to convert a column value while a prepared record is being built by
 * {@link CSVPrinter#printPreparedRecord(Iterable, CSVConverter)}.
 *
 * <p>
 * This is an unchecked exception so that a column conversion failure is never confused with an
 * {@link java.io.IOException} reported by the target output. The two failure modes have different guarantees:
 * </p>
 *
 * <ul>
 * <li>Conversion failure (this exception): the record was still being prepared in a private buffer, so nothing of
 * that record was written to the target {@link Appendable}.</li>
 * <li>Output failure ({@link java.io.IOException}): the record was already fully prepared and being written, so
 * already-written bytes are <em>not</em> rolled back; only the record count is left unincremented.</li>
 * </ul>
 *
 * @since 1.15.0
 */
public class CSVConversionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Zero-based index of the column whose conversion failed. */
    private final int columnIndex;

    /**
     * Constructs a new exception for the column that failed to convert.
     *
     * @param columnIndex zero-based index of the column whose conversion failed.
     * @param cause the exception thrown by the {@link CSVConverter}.
     */
    public CSVConversionException(final int columnIndex, final Throwable cause) {
        super("Conversion failed for CSV column " + columnIndex, cause);
        this.columnIndex = columnIndex;
    }

    /**
     * Gets the zero-based index of the column whose conversion failed.
     *
     * @return the zero-based column index.
     */
    public int getColumnIndex() {
        return columnIndex;
    }
}
