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
 * Converts one business object into the value printed for a single CSV column.
 *
 * <p>
 * A converter is passed to {@link CSVPrinter#printPreparedRecord(Iterable, CSVConverter)} so that domain objects can
 * be mapped to printable column values. The printer invokes {@link #convert(Object)} at most once per column of a
 * record; it never re-invokes a converter to measure output length or to retry.
 * </p>
 *
 * <p>
 * If {@link #convert(Object)} throws any exception, the printer aborts the record before anything is written to the
 * target {@link Appendable} and reports the failure as a {@link CSVConversionException}. This is deliberately
 * distinct from an {@link java.io.IOException}, which is only thrown when the target output itself fails.
 * </p>
 *
 * @param <T> the type of business object converted to a column value.
 * @since 1.15.0
 */
@FunctionalInterface
public interface CSVConverter<T> {

    /**
     * Converts one business object into the value printed for a single CSV column.
     *
     * <p>
     * The returned value is rendered exactly like a value passed to {@link CSVPrinter#print(Object)}: it is escaped
     * or encapsulated as the {@link CSVFormat} requires. Returning {@code null} prints the format's null string.
     * </p>
     *
     * @param value the business object for one column, may be null.
     * @return the value to print for the column, may be null.
     * @throws Exception if the value cannot be converted; the printer wraps it in a {@link CSVConversionException}
     *         and writes nothing for the record being prepared.
     */
    Object convert(T value) throws Exception;
}
