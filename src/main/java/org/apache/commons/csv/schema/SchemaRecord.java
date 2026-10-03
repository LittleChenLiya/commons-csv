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

package org.apache.commons.csv.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.csv.CSVRecord;

/**
 * One data record delivered by a {@link SchemaCSVReader}, already bound to the
 * column names declared by the {@code __schema__} record of its section.
 *
 * <p>Unlike {@link CSVRecord}, a {@code SchemaRecord} always exposes a name to
 * value mapping, because a section only ever yields data records after a valid
 * header has been installed. Instances are immutable snapshots and can be kept
 * after the underlying parser has moved on.</p>
 *
 * @since 1.15.0
 */
public final class SchemaRecord {

    private final long section;

    private final long recordNumber;

    private final long sectionRecordNumber;

    private final List<String> headerNames;

    private final Map<String, String> valuesByName;

    private final String[] values;

    private final String comment;

    SchemaRecord(final long section, final long recordNumber, final long sectionRecordNumber,
            final List<String> headerNames, final CSVRecord record) {
        this.section = section;
        this.recordNumber = recordNumber;
        this.sectionRecordNumber = sectionRecordNumber;
        // Defensive copies: the CSVRecord reuses its backing state through the iterator.
        this.headerNames = Collections.unmodifiableList(new ArrayList<>(headerNames));
        this.values = record.values().clone();
        final Map<String, String> map = new LinkedHashMap<>(headerNames.size());
        for (int i = 0; i < headerNames.size(); i++) {
            map.put(headerNames.get(i), this.values[i]);
        }
        this.valuesByName = Collections.unmodifiableMap(map);
        this.comment = record.getComment();
    }

    /**
     * Gets a field value by declared column name.
     *
     * @param name the column name declared by the section header.
     * @return the decoded field value, never {@code null} unless the format
     *         converts the field to {@code null}.
     * @throws IllegalArgumentException if {@code name} is not a column of this section.
     */
    public String get(final String name) {
        if (!valuesByName.containsKey(Objects.requireNonNull(name, "name"))) {
            throw new IllegalArgumentException("Section " + section + " has no column '" + name + "', columns are "
                    + headerNames);
        }
        return valuesByName.get(name);
    }

    /**
     * Gets a field value by 0-based column position.
     *
     * @param index the 0-based column index.
     * @return the decoded field value.
     * @throws IndexOutOfBoundsException if the index is out of range.
     */
    public String get(final int index) {
        return values[index];
    }

    /**
     * Gets the 1-based section number this record belongs to.
     *
     * @return the section number.
     */
    public long getSection() {
        return section;
    }

    /**
     * Gets the 1-based physical record number within the whole CSV stream.
     *
     * <p>{@code __schema__} declaration records are counted too, so this number
     * matches {@link CSVRecord#getRecordNumber()} of the underlying record.</p>
     *
     * @return the physical CSV record number.
     */
    public long getRecordNumber() {
        return recordNumber;
    }

    /**
     * Gets the 1-based ordinal of this data record within its section.
     *
     * <p>Declaration records are excluded; each section starts counting at one
     * for its first data record.</p>
     *
     * @return the data record ordinal inside the section.
     */
    public long getSectionRecordNumber() {
        return sectionRecordNumber;
    }

    /**
     * Gets the column names declared for this section, in column order.
     *
     * @return the immutable list of header names.
     */
    public List<String> getHeaderNames() {
        return headerNames;
    }

    /**
     * Gets the number of fields, equal to the number of declared columns.
     *
     * @return the field count.
     */
    public int size() {
        return values.length;
    }

    /**
     * Gets an immutable column name to field value map for this record.
     *
     * @return the mapped values in column order.
     */
    public Map<String, String> toMap() {
        return valuesByName;
    }

    /**
     * Gets the decoded field values in column order.
     *
     * @return a fresh array of field values.
     */
    public String[] values() {
        return values.clone();
    }

    /**
     * Gets the comment attached to this record by the underlying parser, if any.
     *
     * @return the comment text, or {@code null} if the record has no comment.
     */
    public String getComment() {
        return comment;
    }

    @Override
    public String toString() {
        return "SchemaRecord[section=" + section + ", record=" + recordNumber + ", sectionRecord="
                + sectionRecordNumber + ", values=" + valuesByName + "]";
    }
}
