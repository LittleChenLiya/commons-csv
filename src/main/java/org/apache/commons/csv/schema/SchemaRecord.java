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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVRecord;

/**
 * A data record bound to the header of the {@linkplain SchemaCSVReader section} it belongs
 * to.
 *
 * <p>
 * Unlike a plain {@link CSVRecord}, a {@code SchemaRecord} resolves column names against
 * the header of its own section rather than a single header shared by the whole stream.
 * When a {@link SchemaCSVReader} switches sections, later records bind to the new header;
 * records already produced keep their own binding.
 * </p>
 *
 * @see SchemaCSVReader
 * @since 1.0
 */
public final class SchemaRecord implements Iterable<String> {

    /** The section this record belongs to (1-based). */
    private final int sectionNumber;

    /** The record number as reported by the underlying parser (1-based). */
    private final long recordNumber;

    /** The underlying raw CSV record. */
    private final CSVRecord record;

    /** The header names of the section this record belongs to, in column order. */
    private final List<String> headerNames;

    /** Mapping of column name to 0-based index within the section. */
    private final Map<String, Integer> headerIndex;

    /** A comment carried over from the schema declaration that opened the section, or null. */
    private final String commentOverride;

    SchemaRecord(final int sectionNumber, final long recordNumber, final CSVRecord record, final List<String> headerNames,
            final Map<String, Integer> headerIndex, final String commentOverride) {
        this.sectionNumber = sectionNumber;
        this.recordNumber = recordNumber;
        this.record = record;
        this.headerNames = headerNames;
        this.headerIndex = headerIndex;
        this.commentOverride = commentOverride;
    }

    /**
     * Gets the section number this record belongs to.
     *
     * @return the section number, 1-based.
     */
    public int getSectionNumber() {
        return sectionNumber;
    }

    /**
     * Gets the record number as reported by the underlying parser.
     *
     * @return the record number, 1-based.
     */
    public long getRecordNumber() {
        return recordNumber;
    }

    /**
     * Gets the header names of the section this record belongs to, in column order.
     *
     * @return an unmodifiable list of header names.
     */
    public List<String> getHeaderNames() {
        return headerNames;
    }

    /**
     * Gets the underlying raw {@link CSVRecord}.
     *
     * @return the underlying record.
     */
    public CSVRecord getRecord() {
        return record;
    }

    /**
     * Gets the comment attached to this record.
     * <p>
     * A comment that immediately precedes a schema declaration is attached to the
     * declaration by the parser and carried over to the first data record of the new
     * section if that record has no comment of its own.
     * </p>
     *
     * @return the comment, or {@code null} if none.
     */
    public String getComment() {
        return record.getComment() != null ? record.getComment() : commentOverride;
    }

    /**
     * Tests whether this record has a comment.
     *
     * @return {@code true} if this record has a comment.
     */
    public boolean hasComment() {
        return getComment() != null;
    }

    /**
     * Gets the number of values in this record.
     *
     * @return the number of values.
     */
    public int size() {
        return record.size();
    }

    /**
     * Gets a value by index.
     *
     * @param index a column index (0-based).
     * @return the value at the given index.
     */
    public String get(final int index) {
        return record.get(index);
    }

    /**
     * Gets a value by column name.
     *
     * @param name the column name.
     * @return the value for the given column name.
     * @throws IllegalArgumentException if the name is not a column of this record's section.
     */
    public String get(final String name) {
        final Integer index = headerIndex.get(name);
        if (index == null) {
            throw new IllegalArgumentException(
                    String.format("Mapping for %s not found, expected one of %s", name, headerNames));
        }
        return record.get(index.intValue());
    }

    /**
     * Tests whether the given column name is mapped for this record's section.
     *
     * @param name the column name.
     * @return {@code true} if the name is a column of this record's section.
     */
    public boolean isMapped(final String name) {
        return name != null && headerIndex.containsKey(name);
    }

    /**
     * Returns an iterator over the values of this record.
     *
     * @return an iterator over the values.
     */
    @Override
    public Iterator<String> iterator() {
        return record.iterator();
    }

    /**
     * Gets the values of this record as an array.
     *
     * @return the values, never null.
     */
    public String[] values() {
        return record.values();
    }

    /**
     * Converts this record into a map of column name to record value.
     *
     * @return a new map; empty if this record's section has no columns.
     */
    public Map<String, String> toMap() {
        final Map<String, String> map = new LinkedHashMap<>(headerNames.size());
        headerNames.forEach(name -> map.put(name, record.get(headerIndex.get(name).intValue())));
        return map;
    }

    /**
     * Returns a sequential ordered stream of the values of this record.
     *
     * @return a stream of values.
     */
    public Stream<String> stream() {
        return record.stream();
    }

    @Override
    public String toString() {
        return "SchemaRecord [sectionNumber=" + sectionNumber + ", recordNumber=" + recordNumber + ", values="
                + toMap() + "]";
    }
}
