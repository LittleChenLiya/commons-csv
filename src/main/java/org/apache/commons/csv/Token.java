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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** Whether this token records field positions in the raw input. */
    boolean trackLocation;

    /** Start position (inclusive) of the verbatim field in the reader, in UTF-16 code units. */
    long rawStart = -1;

    /** End position (exclusive) of the verbatim field in the reader, in UTF-16 code units. */
    long rawEnd = -1;

    /** Parallel arrays describing value-to-raw span mappings, grown as needed. */
    private long[] fragmentRawStart = new long[8];

    private long[] fragmentRawEnd = new long[8];

    private int[] fragmentValueLength = new int[8];

    /** Number of value-to-raw fragments collected for this token. */
    private int fragmentCount;

    /** Adds a raw span decoding to {@code valueLength} UTF-16 units of the field value. */
    void addRawFragment(final long start, final long end, final int valueLength) {
        if (fragmentCount == fragmentRawStart.length) {
            final int newCapacity = fragmentCount * 2;
            fragmentRawStart = Arrays.copyOf(fragmentRawStart, newCapacity);
            fragmentRawEnd = Arrays.copyOf(fragmentRawEnd, newCapacity);
            fragmentValueLength = Arrays.copyOf(fragmentValueLength, newCapacity);
        }
        fragmentRawStart[fragmentCount] = start;
        fragmentRawEnd[fragmentCount] = end;
        fragmentValueLength[fragmentCount] = valueLength;
        fragmentCount++;
    }

    /** Marks the start of the verbatim field at the given reader position. */
    void beginLocation(final long start) {
        trackLocation = true;
        rawStart = start;
        rawEnd = start;
        fragmentCount = 0;
    }

    /** Builds the immutable public location, adding the given source character offset to all raw positions. */
    CSVFieldLocation toFieldLocation(final long characterOffset) {
        final List<CSVValueSpan> spans = new ArrayList<>(fragmentCount);
        int valueStart = 0;
        for (int i = 0; i < fragmentCount; i++) {
            final int valueLength = fragmentValueLength[i];
            spans.add(new CSVValueSpan(fragmentRawStart[i] + characterOffset, fragmentRawEnd[i] + characterOffset, valueStart,
                    valueLength));
            valueStart += valueLength;
        }
        return new CSVFieldLocation(rawStart + characterOffset, rawEnd + characterOffset, spans);
    }

    /**
     * Adjusts the value-to-raw fragments after {@code leading} / {@code trailing} decoded units were trimmed off the
     * field value by the format.
     */
    void trimLocation(final int leading, final int trailing) {
        if (leading == 0 && trailing == 0) {
            return;
        }
        int first = 0;
        int lead = leading;
        while (first < fragmentCount && lead >= fragmentValueLength[first]) {
            lead -= fragmentValueLength[first];
            first++;
        }
        int lastExclusive = fragmentCount;
        int trail = trailing;
        while (lastExclusive > first && trail >= fragmentValueLength[lastExclusive - 1]) {
            trail -= fragmentValueLength[lastExclusive - 1];
            lastExclusive--;
        }
        final int newCount = lastExclusive - first;
        if (first > 0 && newCount > 0) {
            System.arraycopy(fragmentRawStart, first, fragmentRawStart, 0, newCount);
            System.arraycopy(fragmentRawEnd, first, fragmentRawEnd, 0, newCount);
            System.arraycopy(fragmentValueLength, first, fragmentValueLength, 0, newCount);
        }
        fragmentCount = newCount;
        // A fragment can only be partially consumed when it maps decoded units one-to-one onto raw units, so the
        // leftover decoded count equals the raw count to drop.
        if (newCount > 0 && lead > 0) {
            fragmentValueLength[0] -= lead;
            fragmentRawStart[0] += lead;
        }
        if (newCount > 0 && trail > 0) {
            fragmentValueLength[newCount - 1] -= trail;
            fragmentRawEnd[newCount - 1] -= trail;
        }
    }

    void reset() {
        content.setLength(0);
        type = INVALID;
        isReady = false;
        isQuoted = false;
        isEscaped = false;
        trackLocation = false;
        rawStart = -1;
        rawEnd = -1;
        fragmentCount = 0;
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
