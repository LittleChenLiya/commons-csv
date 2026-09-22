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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of applying a {@link CSVProjection} to a single {@link CSVRecord}.
 *
 * <p>
 * Values are addressed by output alias (or by projection index) and follow the same value rules as
 * {@link CSVRecord#get(int)}. The originating record number, character position and byte position are preserved from
 * the source record.
 * </p>
 *
 * @since 1.15.0
 */
public final class ProjectedRecord {

    /** The output aliases, in projection order. */
    private final List<String> aliases;

    /** The projected values, aligned with {@link #aliases}. */
    private final String[] values;

    /** The record number of the source record. */
    private final long recordNumber;

    /** The character position of the source record. */
    private final long characterPosition;

    /** The byte position of the source record. */
    private final long bytePosition;

    ProjectedRecord(final List<String> aliases, final String[] values, final long recordNumber, final long characterPosition,
            final long bytePosition) {
        this.aliases = aliases;
        this.values = values;
        this.recordNumber = recordNumber;
        this.characterPosition = characterPosition;
        this.bytePosition = bytePosition;
    }

    /**
     * Gets a projected value by output alias.
     *
     * @param alias the output alias of the column to be retrieved.
     * @return the projected value, may be null depending on the projection configuration.
     * @throws IllegalArgumentException if the alias is not part of this projection.
     */
    public String get(final String alias) {
        final int index = aliases.indexOf(alias);
        if (index < 0) {
            throw new IllegalArgumentException(String.format("Alias %s not found, expected one of %s", alias, aliases));
        }
        return values[index];
    }

    /**
     * Gets a projected value by projection index.
     *
     * @param i a projection index (0-based).
     * @return the projected value, may be null depending on the projection configuration.
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds.
     */
    public String get(final int i) {
        return values[i];
    }

    /**
     * Gets the output aliases of this projection, in projection order.
     *
     * @return the output aliases.
     */
    public List<String> getAliases() {
        return Collections.unmodifiableList(aliases);
    }

    /**
     * Gets the starting position of the source record in the source stream, measured in bytes.
     *
     * @return the byte position of the source record.
     */
    public long getBytePosition() {
        return bytePosition;
    }

    /**
     * Gets the start position of the source record as a character position in the source stream.
     *
     * @return the character position of the source record.
     */
    public long getCharacterPosition() {
        return characterPosition;
    }

    /**
     * Gets the number of the source record in the parsed CSV file.
     *
     * @return the number of the source record.
     */
    public long getRecordNumber() {
        return recordNumber;
    }

    /**
     * Returns the number of projected values.
     *
     * @return the number of projected values.
     */
    public int size() {
        return values.length;
    }

    /**
     * Copies this projected record into a new Map of output alias to projected value.
     *
     * <p>
     * Editing the map does not update this instance.
     * </p>
     *
     * @return a new Map of output alias to projected value.
     */
    public Map<String, String> toMap() {
        final Map<String, String> map = new LinkedHashMap<>(aliases.size());
        for (int i = 0; i < aliases.size(); i++) {
            map.put(aliases.get(i), values[i]);
        }
        return map;
    }

    /**
     * Returns a string representation of the contents of this projected record.
     *
     * @return a String representation of this projected record.
     */
    @Override
    public String toString() {
        return "ProjectedRecord [recordNumber=" + recordNumber + ", characterPosition=" + characterPosition + ", bytePosition=" + bytePosition
                + ", values=" + toMap() + "]";
    }

    /**
     * Gets the projected values for this record. This is <strong>not</strong> a copy.
     *
     * @return the projected values, never null.
     */
    public String[] values() {
        return values;
    }
}
