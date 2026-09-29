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

import java.util.Collections;
import java.util.List;

/**
 * The location of a single CSV field in the raw input consumed by a {@link java.io.Reader}.
 * <p>
 * Locations are only available when the parser format enables field position tracking with
 * {@link CSVFormat.Builder#setTrackFieldPositions(boolean)}. All offsets are zero-based UTF-16 code unit positions
 * measured from the start of the reader, the same unit as {@link String#length()} and
 * {@link String#substring(int, int)}, so slicing the original input text with
 * {@code text.substring(location.getRawStart(), location.getRawEnd())} returns the verbatim field text.
 * </p>
 * <p>
 * The {@code raw} span covers the complete field including the original surrounding quotes and escape
 * sequences, and spans the intervening end-of-line characters for fields spread over multiple physical lines.
 * It does not include the field delimiter or the record terminator. For an empty field produced by two
 * adjacent delimiters, {@code getRawStart()} equals {@code getRawEnd()} and points at the delimiter position.
 * </p>
 * <p>
 * The {@link #getValueSpans() value spans} map each contiguous run of the decoded field value onto the raw
 * span it was decoded from. This highlights the source of an individual value character when quoting or
 * escaping hides the decoded text.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVFieldLocation {

    private final long rawStart;

    private final long rawEnd;

    private final List<CSVValueSpan> valueSpans;

    CSVFieldLocation(final long rawStart, final long rawEnd, final List<CSVValueSpan> valueSpans) {
        this.rawStart = rawStart;
        this.rawEnd = rawEnd;
        this.valueSpans = valueSpans == null ? Collections.emptyList() : Collections.unmodifiableList(valueSpans);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final CSVFieldLocation other = (CSVFieldLocation) obj;
        return rawStart == other.rawStart && rawEnd == other.rawEnd && valueSpans.equals(other.valueSpans);
    }

    /**
     * Gets the end position (exclusive) of the verbatim field text in the raw input.
     * <p>
 * The raw span includes the original surrounding quotes, escape sequences and, for fields spread over
     * multiple physical lines, the end-of-line sequences. It excludes the field delimiter and record
     * terminator.
     * </p>
     *
     * @return The zero-based end position (exclusive) in UTF-16 code units of the raw input.
     */
    public long getRawEnd() {
        return rawEnd;
    }

    /**
     * Gets the start position (inclusive) of the verbatim field text in the raw input.
     * <p>
     * For a quoted field this points at the opening quote character; for an empty field it points at the
     * following delimiter or record terminator.
     * </p>
     *
     * @return The zero-based start position in UTF-16 code units of the raw input.
     */
    public long getRawStart() {
        return rawStart;
    }

    /**
     * Maps the decoded field value onto the raw input spans it was decoded from.
     * <p>
     * The returned spans are ordered and non-overlapping. Together they cover the whole decoded field value.
     * A span may cover more raw characters than decoded characters because quoting and escaping is removed;
     * raw end-of-line characters inside a quoted field are kept as-is and map one-to-one.
     * </p>
     *
     * @return An unmodifiable list of value-to-raw span mappings, never null; empty for a field with an empty value.
     */
    public List<CSVValueSpan> getValueSpans() {
        return valueSpans;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(rawStart);
        result = 31 * result + Long.hashCode(rawEnd);
        result = 31 * result + valueSpans.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "CSVFieldLocation [rawStart=" + rawStart + ", rawEnd=" + rawEnd + ", valueSpans=" + valueSpans + "]";
    }
}
