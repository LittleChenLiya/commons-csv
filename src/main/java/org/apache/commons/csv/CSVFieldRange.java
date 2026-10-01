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

import java.io.Serializable;
import java.util.Arrays;

/**
 * Locates a single field of a {@link CSVRecord} in the original input.
 * <p>
 * Instances are only available when the parser runs with position tracking enabled, see
 * {@link CSVFormat.Builder#setTrackPositions(boolean)}. All offsets are UTF-16 character offsets (the same unit as
 * {@link CSVRecord#getCharacterPosition()}), counted from the start of the input as seen by the parser, including any
 * character offset configured on the parser.
 * </p>
 * <p>
 * The {@linkplain #getStartOffset() field range} covers the raw source text of the field, including the quote
 * characters and escape sequences that were removed while decoding; it excludes the delimiter or line terminator that
 * ends the field. The {@linkplain #getValueStartOffset(int) value mapping} maps every character of the decoded value
 * (as returned by {@link CSVRecord#get(int)}) back to the half-open source range it was decoded from, so multi-line
 * fields, doubled quotes and escape sequences can be highlighted individually.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVFieldRange implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Offset of the first source character of the raw field text. */
    private final long startOffset;

    /** Offset one past the last source character of the raw field text. */
    private final long endOffset;

    /** Flattened half-open source ranges, two entries (start, end) per decoded value character. */
    private final long[] valueRanges;

    /**
     * Constructs a new instance.
     *
     * @param startOffset Offset of the first source character of the raw field text.
     * @param endOffset Offset one past the last source character of the raw field text.
     * @param valueRanges Flattened half-open source ranges, two entries per decoded value character; copied, may be
     *        null for an empty mapping.
     */
    CSVFieldRange(final long startOffset, final long endOffset, final long[] valueRanges) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.valueRanges = valueRanges != null ? valueRanges.clone() : Constants.EMPTY_LONG_ARRAY;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CSVFieldRange)) {
            return false;
        }
        final CSVFieldRange other = (CSVFieldRange) obj;
        return startOffset == other.startOffset && endOffset == other.endOffset && Arrays.equals(valueRanges, other.valueRanges);
    }

    /**
     * Gets the offset one past the last source character of the raw field text, including quotes and escape
     * sequences, excluding the trailing delimiter or line terminator.
     *
     * @return The exclusive end offset of the raw field text.
     */
    public long getEndOffset() {
        return endOffset;
    }

    /**
     * Gets the offset of the first source character of the raw field text, including the opening quote character when
     * the field is quoted.
     *
     * @return The inclusive start offset of the raw field text.
     */
    public long getStartOffset() {
        return startOffset;
    }

    /**
     * Gets the offset one past the last source character that produced the decoded value character at the given
     * index.
     *
     * @param index A 0-based index into the decoded value.
     * @return The exclusive end offset of the source range for the decoded character.
     * @throws IndexOutOfBoundsException If the index is out of range.
     */
    public long getValueEndOffset(final int index) {
        return valueRanges[checkIndex(index) * 2 + 1];
    }

    /**
     * Gets the number of decoded value characters covered by the value mapping. This is the length of the
     * corresponding {@link CSVRecord} value.
     *
     * @return The number of mapped decoded value characters.
     */
    public int getValueLength() {
        return valueRanges.length / 2;
    }

    /**
     * Gets the offset of the first source character that produced the decoded value character at the given index. For
     * an escaped or doubled character the range covers the whole source sequence, for example both characters of a
     * doubled quote.
     *
     * @param index A 0-based index into the decoded value.
     * @return The inclusive start offset of the source range for the decoded character.
     * @throws IndexOutOfBoundsException If the index is out of range.
     */
    public long getValueStartOffset(final int index) {
        return valueRanges[checkIndex(index) * 2];
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Long.hashCode(startOffset);
        result = prime * result + Long.hashCode(endOffset);
        result = prime * result + Arrays.hashCode(valueRanges);
        return result;
    }

    private int checkIndex(final int index) {
        if (index < 0 || index >= getValueLength()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for value length " + getValueLength());
        }
        return index;
    }

    /**
     * Returns a string representation of this range, useful for debugging.
     *
     * @return A string representation of this range.
     */
    @Override
    public String toString() {
        return "CSVFieldRange [startOffset=" + startOffset + ", endOffset=" + endOffset + ", valueLength=" + getValueLength() + "]";
    }
}
