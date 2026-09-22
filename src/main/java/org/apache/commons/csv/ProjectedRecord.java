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
 * Values are addressed by alias (or by projection index). The original record number, character position and byte
 * position of the source record are preserved and delegated to the source {@link CSVRecord}.
 * </p>
 *
 * @since 1.15.0
 */
public final class ProjectedRecord {

    private final List<String> aliases;

    private final Map<String, Integer> aliasMap;

    private final String[] values;

    private final CSVRecord sourceRecord;

    ProjectedRecord(final List<String> aliases, final String[] values, final CSVRecord sourceRecord) {
        this.aliases = aliases;
        this.values = values;
        this.sourceRecord = sourceRecord;
        final Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < aliases.size(); i++) {
            map.put(aliases.get(i), Integer.valueOf(i));
        }
        this.aliasMap = Collections.unmodifiableMap(map);
    }

    /**
     * Gets the aliases of this projection, in projection order.
     *
     * @return the aliases, unmodifiable.
     */
    public List<String> aliases() {
        return aliases;
    }

    /**
     * Gets a value by alias.
     *
     * @param alias the alias of the projected column.
     * @return the projected value, possibly {@code null} depending on the configured policies.
     * @throws IllegalArgumentException if the alias is not part of this projection.
     */
    public String get(final String alias) {
        final Integer index = aliasMap.get(alias);
        if (index == null) {
            throw new IllegalArgumentException("No projected column for alias '" + alias + "'");
        }
        return values[index.intValue()];
    }

    /**
     * Gets a value by projection index.
     *
     * @param i a projection index (0-based).
     * @return the projected value, possibly {@code null} depending on the configured policies.
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds.
     */
    public String get(final int i) {
        return values[i];
    }

    /**
     * Gets the byte position of the source record in the input, see {@link CSVRecord#getBytePosition()}.
     *
     * @return the byte position of the source record.
     */
    public long getBytePosition() {
        return sourceRecord.getBytePosition();
    }

    /**
     * Gets the character position of the source record in the input, see {@link CSVRecord#getCharacterPosition()}.
     *
     * @return the character position of the source record.
     */
    public long getCharacterPosition() {
        return sourceRecord.getCharacterPosition();
    }

    /**
     * Gets the original record number of the source record, see {@link CSVRecord#getRecordNumber()}.
     *
     * @return the original record number.
     */
    public long getRecordNumber() {
        return sourceRecord.getRecordNumber();
    }

    /**
     * Gets the source record this projection was computed from.
     *
     * @return the source record.
     */
    public CSVRecord getSourceRecord() {
        return sourceRecord;
    }

    /**
     * Gets the number of projected columns.
     *
     * @return the number of projected columns.
     */
    public int size() {
        return values.length;
    }

    /**
     * Converts this projected record to a map from alias to value, in projection order.
     *
     * @return a new map from alias to value.
     */
    public Map<String, String> toMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < aliases.size(); i++) {
            map.put(aliases.get(i), values[i]);
        }
        return map;
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
