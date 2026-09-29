/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *         https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.csv;

/**
 * Maps a contiguous run of a decoded field value onto the span of the raw input from which the run was decoded.
 * <p>
 * Offsets are UTF-16 code unit positions measured from the start of the {@link java.io.Reader} consumed by the
 * parser, as documented by {@link CSVRecord#getCharacterPosition()}. The raw span always includes the original
 * quote and escape characters, and it may span multiple physical lines for fields containing end-of-line
 * sequences. A raw span can therefore be longer than the decoded run it maps to, for example the two raw
 * characters {@code ""} decode to the single quote character {@code \"}, and the two raw characters {@code \\t}
 * decode to a tabulator.
 * </p>
 * <p>
 * The spans for a value are ordered, do not overlap, and the concatenation of {@code valueLength} units over all
 * spans covers the whole decoded field value.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVValueSpan {

    private final long rawStart;

    private final long rawEnd;

    private final int valueStart;

    private final int valueLength;

    CSVValueSpan(final long rawStart, final long rawEnd, final int valueStart, final int valueLength) {
        this.rawStart = rawStart;
        this.rawEnd = rawEnd;
        this.valueStart = valueStart;
        this.valueLength = valueLength;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final CSVValueSpan other = (CSVValueSpan) obj;
        return rawStart == other.rawStart && rawEnd == other.rawEnd && valueStart == other.valueStart
                && valueLength == other.valueLength;
    }

    /**
     * Gets the end position (exclusive) of this run in the raw input.
     * <p>
     * The raw span includes original quote and escape characters and may span multiple lines.
     * </p>
     *
     * @return The zero-based end position (exclusive) in UTF-16 code units of the raw input.
     */
    public long getRawEnd() {
        return rawEnd;
    }

    /**
     * Gets the start position (inclusive) of this run in the raw input.
     * <p>
     * The raw span includes original quote and escape characters and may span multiple lines.
     * </p>
     *
     * @return The zero-based start position in UTF-16 code units of the raw input.
     */
    public long getRawStart() {
        return rawStart;
    }

    /**
     * Gets the number of UTF-16 code units occupied by the decoded run in the field value.
     *
     * @return The length of the decoded run, always a positive number.
     */
    public int getValueLength() {
        return valueLength;
    }

    /**
     * Gets the start index (inclusive) of this run within the decoded field value.
     *
     * @return The zero-based start index of the decoded run within the field value.
     */
    public int getValueStart() {
        return valueStart;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(rawStart);
        result = 31 * result + Long.hashCode(rawEnd);
        result = 31 * result + valueStart;
        result = 31 * result + valueLength;
        return result;
    }

    @Override
    public String toString() {
        return "CSVValueSpan [rawStart=" + rawStart + ", rawEnd=" + rawEnd + ", valueStart=" + valueStart
                + ", valueLength=" + valueLength + "]";
    }
}
