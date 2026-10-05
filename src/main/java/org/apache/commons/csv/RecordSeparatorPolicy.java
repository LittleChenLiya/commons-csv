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
 * Defines how the parser validates record separators (line terminators) while reading input.
 *
 * <p>
 * The policy only applies to record separators seen by the parser, that is, line terminators outside of quoted values and comment lines:
 * </p>
 * <ul>
 * <li>Line breaks inside a quoted (encapsulated) value are part of the value and never participate in validation.</li>
 * <li>The line terminator of a comment line never locks nor violates the policy.</li>
 * <li>The header record counts as a record: its terminator is validated like any other record terminator.</li>
 * <li>A final record at end-of-file needs no terminator at all; end-of-file without a record separator is always legal.</li>
 * </ul>
 *
 * <p>
 * A policy violation fails the parse with a {@link CSVException} carrying the record number and character position before the offending
 * {@link CSVRecord} is delivered.
 * </p>
 *
 * @since 1.15.0
 */
public enum RecordSeparatorPolicy {

    /**
     * Accepts {@code CR}, {@code LF}, and {@code CRLF} record separators, mixed or not. This is the default and historical behavior.
     */
    ANY(null),

    /**
     * Requires every record separator to be a single {@code LF} ({@code \n}).
     */
    LF("\n"),

    /**
     * Requires every record separator to be a single {@code CR} ({@code \r}).
     */
    CR("\r"),

    /**
     * Requires every record separator to be {@code CRLF} ({@code \r\n}), recognized as a unit even when the two characters are delivered
     * in separate chunks by the underlying reader.
     */
    CRLF("\r\n"),

    /**
     * Locks the required record separator to the first real record terminator encountered and rejects any different terminator afterwards.
     */
    CONSISTENT(null);

    /** The record separator this policy requires, or {@code null} when not fixed. */
    private final String separator;

    RecordSeparatorPolicy(final String separator) {
        this.separator = separator;
    }

    /**
     * Gets the record separator this policy requires.
     *
     * @return The required record separator, or {@code null} for {@link #ANY} and {@link #CONSISTENT}.
     */
    String getSeparator() {
        return separator;
    }
}
