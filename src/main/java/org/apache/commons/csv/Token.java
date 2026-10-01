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

import static org.apache.commons.csv.Token.Type.INVALID;

/**
 * Internal token representation.
 * <p>
 * This is used as a contract between the lexer and the parser.
 * </p>
 */
final class Token {

    enum Type {

        /** Token has no valid content, that is, is in its initialized state. */
        INVALID,

        /** Token with content, at the beginning or in the middle of a line. */
        TOKEN,

        /** Token (which can have content) when the end of file is reached. */
        EOF,

        /** Token with content when the end of a line is reached. */
        EORECORD,

        /** Token is a comment line. */
        COMMENT
    }

    /** Length of the initial token (content-)buffer */
    private static final int DEFAULT_CAPACITY = 50;

    /** Token type */
    Token.Type type = INVALID;

    /** The content buffer, never null. */
    final StringBuilder content = new StringBuilder(DEFAULT_CAPACITY);

    /** Token ready flag: indicates a valid token with content (ready for the parser). */
    boolean isReady;

    boolean isQuoted;

    /** True when an escape sequence in the input was translated while building {@link #content}. */
    boolean isEscaped;

    /**
     * Offset of the first source character of this token, or -1 when positions are not tracked. Includes the opening
     * quote of an encapsulated token.
     */
    long rangeStart = -1;

    /** Offset one past the last source character of this token, or -1 when positions are not tracked. */
    long rangeEnd = -1;

    /**
     * Flattened half-open source ranges, two entries (start, end) per character of {@link #content}. Only the first
     * {@code 2 * valueRangeCount} entries are valid. Grows as necessary and is reused.
     */
    long[] valueRanges = new long[2 * DEFAULT_CAPACITY];

    /** The number of content characters for which {@link #valueRanges} holds a source range. */
    int valueRangeCount;

    /**
     * Appends a source range for one content character.
     *
     * @param start inclusive source offset.
     * @param end exclusive source offset.
     */
    void addValueRange(final long start, final long end) {
        if (valueRanges.length < 2 * (valueRangeCount + 1)) {
            final long[] grown = new long[2 * Math.max(valueRanges.length, 2 * (valueRangeCount + 1))];
            System.arraycopy(valueRanges, 0, grown, 0, valueRanges.length);
            valueRanges = grown;
        }
        valueRanges[2 * valueRangeCount] = start;
        valueRanges[2 * valueRangeCount + 1] = end;
        valueRangeCount++;
    }

    void reset() {
        content.setLength(0);
        type = INVALID;
        isReady = false;
        isQuoted = false;
        isEscaped = false;
        rangeStart = -1;
        rangeEnd = -1;
        valueRangeCount = 0;
    }

    /**
     * Truncates the tracked value ranges to {@code length} characters, mirroring a truncation of {@link #content}.
     *
     * @param length the new content length.
     */
    void truncateValueRanges(final int length) {
        if (valueRangeCount > length) {
            valueRangeCount = length;
        }
    }

    /**
     * Converts the token state to a string to ease debugging.
     *
     * @return A string helpful for debugging.
     */
    @Override
    public String toString() {
        return type + " [" + content.toString() + "]";
    }
}
