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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Command-line demo of {@link CsvValidator}: prints typed values for valid records and the aggregated structured
 * errors (with record number and physical start line) for invalid ones.
 *
 * <pre>
 * java -cp target/classes org.apache.commons.csv.CsvValidatorDemo
 * </pre>
 */
public final class CsvValidatorDemo {

    private enum Status {
        NEW, PAID, CANCELLED
    }

    public static void main(final String[] args) throws Exception {
        final long start = System.nanoTime();

        final ColumnRule id = ColumnRule.integer("id");
        final ColumnRule amount = ColumnRule.decimal("amount");
        final ColumnRule when = ColumnRule.date("when");
        final ColumnRule status = ColumnRule.enumeration("status", Status.class);
        final ColumnRule discount = ColumnRule.decimal("discount").nullable();

        final CsvValidator validator = CsvValidator.builder()
                .rule(id).rule(amount).rule(when).rule(status).rule(discount)
                .maxErrors(10)
                .build();

        // Line 1:  header
        // Line 2:  valid record, exact high-precision amount
        // Line 3:  valid record with a null token in the nullable column
        // Line 4-5: record with a quoted newline, an invalid date AND an invalid enum (two errors, one row)
        // Line 6:  record too short: missing columns reported per rule
        // Line 7:  valid record proving validation continues after errors
        final String csv = "id,amount,when,status,discount\n"
                + "1,9007199254740993.01,2026-09-30,PAID,0.05\n"
                + "2,10.00,2026-10-01,NEW,NULL\n"
                + "3,20.00,\"not-a-date\nat-all\",UNKNOWN,\n"
                + "4,30.00\n"
                + "5,99.99,2026-12-31,CANCELLED,\n";

        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setNullString("NULL").get();

        final ValidationResult result;
        try (CSVParser parser = CSVParser.parse(csv, format)) {
            result = validator.validate(parser);
        }

        System.out.println("=== CsvValidator demo ===");
        for (final ValidatedRecord record : result.getRecords()) {
            if (record.isValid()) {
                final Long idValue = record.getLong(id);
                final BigDecimal amountValue = record.getDecimal(amount);
                final LocalDate whenValue = record.getDate(when);
                final Status statusValue = (Status) record.get(status);
                final BigDecimal discountValue = record.getDecimal(discount);
                System.out.printf("record %d (line %d) OK: id=%d (%s), amount=%s (%s), when=%s (%s), status=%s (%s), discount=%s%n",
                        record.getRecordNumber(), record.getLineNumber(),
                        idValue, idValue.getClass().getSimpleName(),
                        amountValue.toPlainString(), amountValue.getClass().getSimpleName(),
                        whenValue, whenValue.getClass().getSimpleName(),
                        statusValue, statusValue.getClass().getSimpleName(),
                        discountValue);
            } else {
                System.out.printf("record %d (line %d) INVALID, %d error(s):%n",
                        record.getRecordNumber(), record.getLineNumber(), record.getErrors().size());
                for (final ValidationError error : record.getErrors()) {
                    System.out.printf("  - [%s] column=%d header=%s raw=%s: %s%n",
                            error.getKind(), error.getColumnIndex(), error.getHeader(), error.getRawValue(),
                            error.getMessage());
                }
            }
        }
        System.out.printf("summary: records=%d, errors=%d, truncated=%s, elapsed=%d ms (< 90 s)%n",
                result.getRecords().size(), result.getErrorCount(), result.isTruncated(),
                (System.nanoTime() - start) / 1000000);
    }

    private CsvValidatorDemo() {
    }
}
