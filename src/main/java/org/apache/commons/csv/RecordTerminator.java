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
 * Defines the strategy used to recognize record terminators when parsing.
 *
 * <p>
 * The parser can be configured to accept a single, fixed terminator ({@link #LF}, {@link #CRLF}, or {@link #CR}), to lock onto the first real record
 * terminator encountered and reject records that use a different one ({@link #LOCK_FIRST}), or to accept any of the three terminators ({@link #ANY}, the
 * default, which preserves the original behavior).
 * </p>
 *
 * <p>
 * Only terminators that actually end a record participate in the strategy. Line breaks inside a quoted field are part of the field value and are ignored, and
 * comment lines are consumed without locking the format. A file that ends without a terminator is still considered to end normally.
 * </p>
 *
 * @see CSVFormat.Builder#setRecordTerminator(RecordTerminator)
 * @since 1.15.0
 */
public enum RecordTerminator {

    /**
     * Accepts any of LF, CR, or CRLF. This is the default and preserves the original behavior of accepting all three terminators interchangeably.
     */
    ANY(null),

    /**
     * Locks onto the first real record terminator encountered and rejects every record that uses a different terminator.
     *
     * <p>
     * The header record counts as a record, so its terminator is the one that locks the format. Line breaks inside a quoted field do not participate, and
     * comment lines do not lock the format.
     * </p>
     */
    LOCK_FIRST(null),

    /**
     * Requires LF ({@code "\n"}) as the record terminator.
     */
    LF("\n"),

    /**
     * Requires CRLF ({@code "\r\n"}) as the record terminator.
     */
    CRLF("\r\n"),

    /**
     * Requires CR ({@code "\r"}) as the record terminator.
     */
    CR("\r");

    private final String terminator;

    RecordTerminator(final String terminator) {
        this.terminator = terminator;
    }

    /**
     * Gets the fixed terminator string required by this strategy, or {@code null} for {@link #ANY} and {@link #LOCK_FIRST}.
     *
     * @return the fixed terminator string, or {@code null}.
     */
    public String getTerminator() {
        return terminator;
    }
}
