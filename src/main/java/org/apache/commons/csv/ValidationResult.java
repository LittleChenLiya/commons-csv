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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The aggregate outcome of running a {@link CsvValidator} over a {@link CSVParser}.
 *
 * <p>
 * Validation continues after invalid records so that later, valid records are still returned. When the configured
 * maximum number of errors is reached, validation stops early and {@link #isTruncated()} reports whether unprocessed
 * input remained.
 * </p>
 *
 * @since 1.15.0
 */
public final class ValidationResult {

    private final List<ValidatedRecord> records;
    private final boolean truncated;

    ValidationResult(final List<ValidatedRecord> records, final boolean truncated) {
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
        this.truncated = truncated;
    }

    /**
     * Gets the number of errors aggregated over all validated records.
     *
     * @return the total error count.
     */
    public int getErrorCount() {
        int count = 0;
        for (final ValidatedRecord record : records) {
            count += record.getErrors().size();
        }
        return count;
    }

    /**
     * Gets all errors of all validated records, in record and column order.
     *
     * @return the flattened error list, never {@code null}.
     */
    public List<ValidationError> getErrors() {
        final List<ValidationError> all = new ArrayList<>();
        for (final ValidatedRecord record : records) {
            all.addAll(record.getErrors());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Gets only the records that failed validation.
     *
     * @return the invalid records, never {@code null}.
     */
    public List<ValidatedRecord> getInvalidRecords() {
        final List<ValidatedRecord> invalid = new ArrayList<>();
        for (final ValidatedRecord record : records) {
            if (!record.isValid()) {
                invalid.add(record);
            }
        }
        return Collections.unmodifiableList(invalid);
    }

    /**
     * Gets every validated record, valid or invalid, in input order.
     *
     * @return the validated records, never {@code null}.
     */
    public List<ValidatedRecord> getRecords() {
        return records;
    }

    /**
     * Tests whether any validated record has errors.
     *
     * @return {@code true} when at least one error was found.
     */
    public boolean hasErrors() {
        for (final ValidatedRecord record : records) {
            if (!record.isValid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests whether validation stopped because the configured maximum number of errors was reached while unprocessed
     * input remained.
     *
     * @return {@code true} when the result does not cover the whole input.
     */
    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public String toString() {
        return "ValidationResult[records=" + records.size() + ", errors=" + getErrorCount() + ", truncated=" + truncated + "]";
    }
}
