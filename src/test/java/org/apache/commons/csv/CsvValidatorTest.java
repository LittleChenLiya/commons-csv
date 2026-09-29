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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.ValidationError.Kind;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CsvValidator}, {@link ColumnRule}, {@link ValidatedRecord}, {@link ValidationError} and
 * {@link ValidationResult}.
 */
class CsvValidatorTest {

    private enum Status {
        NEW, PAID, CANCELLED
    }

    private static CSVFormat withHeader() {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
    }

    @Test
    void testDecimalPrecisionPreservedExactly() throws IOException {
        // 9007199254740993.01 cannot be represented as a double; routing through double would corrupt it.
        final ColumnRule amount = ColumnRule.decimal("amount");
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer("id"), amount);
        final ValidationResult result = validator.validate(CSVParser.parse("id,amount\n1,9007199254740993.01\n", withHeader()));
        assertFalse(result.hasErrors());
        assertFalse(result.isTruncated());
        final BigDecimal value = result.getRecords().get(0).getDecimal(amount);
        assertEquals(new BigDecimal("9007199254740993.01"), value);
        assertEquals("9007199254740993.01", value.toPlainString());
    }

    @Test
    void testInvalidDateAndMissingColumnReportedSeparatelyAndNextRecordReadable() throws IOException {
        final ColumnRule id = ColumnRule.integer("id");
        final ColumnRule when = ColumnRule.date("when");
        final ColumnRule amount = ColumnRule.decimal("amount");
        final CsvValidator validator = CsvValidator.of(id, when, amount);
        // Record 1: bad date AND too few columns (amount missing). Record 2: fully valid.
        final String csv = "id,when,amount\n7,not-a-date\n8,2026-09-30,12.50\n";
        final ValidationResult result = validator.validate(CSVParser.parse(csv, withHeader()));
        assertEquals(2, result.getRecords().size());
        final ValidatedRecord bad = result.getRecords().get(0);
        assertFalse(bad.isValid());
        assertEquals(2, bad.getErrors().size());
        assertEquals(Kind.INVALID_DATE, bad.getErrors().get(0).getKind());
        assertEquals("not-a-date", bad.getErrors().get(0).getRawValue());
        assertEquals(Kind.MISSING_COLUMN, bad.getErrors().get(1).getKind());
        assertEquals(2, bad.getErrors().get(1).getColumnIndex());
        assertEquals(Long.valueOf(7), bad.getLong(id));
        assertEquals(1, bad.getRecordNumber());
        assertEquals(2, bad.getLineNumber());
        final ValidatedRecord good = result.getRecords().get(1);
        assertTrue(good.isValid());
        assertEquals(Long.valueOf(8), good.getLong(id));
        assertEquals(LocalDate.of(2026, 9, 30), good.getDate(when));
        assertEquals(new BigDecimal("12.50"), good.getDecimal(amount));
    }

    @Test
    void testPhysicalLineNumbersWithQuotedNewlines() throws IOException {
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer("id"), ColumnRule.decimal("amount"));
        // Record 1 spans physical lines 2-3 because of the quoted newline; record 2 starts on line 4.
        final String csv = "id,amount\n1,\"9\n99\"\n2,oops\n";
        final ValidationResult result = validator.validate(CSVParser.parse(csv, withHeader()));
        final List<ValidatedRecord> records = result.getRecords();
        assertEquals(2, records.size());
        assertEquals(2, records.get(0).getLineNumber());
        assertEquals(1, records.get(0).getRecordNumber());
        assertEquals(4, records.get(1).getLineNumber());
        assertEquals(2, records.get(1).getRecordNumber());
        final ValidationError error = records.get(1).getErrors().get(0);
        assertEquals(Kind.INVALID_DECIMAL, error.getKind());
        assertEquals(4, error.getLineNumber());
        assertEquals(2, error.getRecordNumber());
    }

    @Test
    void testDuplicateHeaders() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL).get();
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer("id"), ColumnRule.integer(1));
        final ValidationResult result = validator.validate(CSVParser.parse("id,id\n5,6\n", format));
        final ValidatedRecord record = result.getRecords().get(0);
        assertFalse(record.isValid());
        assertEquals(1, record.getErrors().size());
        assertEquals(Kind.DUPLICATE_HEADER, record.getErrors().get(0).getKind());
        assertEquals("id", record.getErrors().get(0).getHeader());
        // The position-based rule still resolves and validates.
        assertEquals(Long.valueOf(6), record.getLong(ColumnRule.integer(1)));
    }

    @Test
    void testMissingHeader() throws IOException {
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer("nope"));
        final ValidationResult result = validator.validate(CSVParser.parse("id\n1\n", withHeader()));
        final ValidationError error = result.getRecords().get(0).getErrors().get(0);
        assertEquals(Kind.MISSING_HEADER, error.getKind());
        assertEquals(-1, error.getColumnIndex());
    }

    @Test
    void testEmptyStringNullTokenAndMissingColumnAreDistinct() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setNullString("NULL").get();
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer(0), ColumnRule.integer(1), ColumnRule.integer(2));
        final ValidationResult result = validator.validate(CSVParser.parse(",NULL\n", format));
        final List<ValidationError> errors = result.getRecords().get(0).getErrors();
        assertEquals(3, errors.size());
        assertEquals(Kind.EMPTY_VALUE, errors.get(0).getKind());
        assertEquals("", errors.get(0).getRawValue());
        assertEquals(Kind.NULL_VALUE, errors.get(1).getKind());
        assertNull(errors.get(1).getRawValue());
        assertEquals(Kind.MISSING_COLUMN, errors.get(2).getKind());
        assertNull(errors.get(2).getRawValue());
    }

    @Test
    void testNullableColumns() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setNullString("NULL").get();
        final ColumnRule amount = ColumnRule.decimal(0).nullable();
        final ColumnRule when = ColumnRule.date(1).nullable();
        final CsvValidator validator = CsvValidator.of(amount, when);
        final ValidationResult result = validator.validate(CSVParser.parse(",NULL\n1.25,2026-01-31\n", format));
        assertFalse(result.hasErrors());
        final ValidatedRecord empty = result.getRecords().get(0);
        assertTrue(empty.isValid());
        assertNull(empty.getDecimal(amount));
        assertNull(empty.getDate(when));
        final ValidatedRecord filled = result.getRecords().get(1);
        assertEquals(new BigDecimal("1.25"), filled.getDecimal(amount));
        assertEquals(LocalDate.of(2026, 1, 31), filled.getDate(when));
    }

    @Test
    void testLocaleIndependentParsing() throws IOException {
        final Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
        try {
            final ColumnRule when = ColumnRule.date("when", "dd/MM/uuuu");
            final ColumnRule amount = ColumnRule.decimal("amount");
            final CsvValidator validator = CsvValidator.of(when, amount);
            final ValidationResult result = validator.validate(CSVParser.parse("when,amount\n30/09/2026,1234.56\n", withHeader()));
            assertFalse(result.hasErrors());
            assertEquals(LocalDate.of(2026, 9, 30), result.getRecords().get(0).getDate(when));
            assertEquals(new BigDecimal("1234.56"), result.getRecords().get(0).getDecimal(amount));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void testEnumRules() throws IOException {
        final ColumnRule byName = ColumnRule.enumeration("status", "NEW", "PAID");
        final ColumnRule byClass = ColumnRule.enumeration("state", Status.class);
        final CsvValidator validator = CsvValidator.of(byName, byClass);
        final ValidationResult result = validator.validate(CSVParser.parse("status,state\nNEW,PAID\nUNKNOWN,PAID\n", withHeader()));
        final ValidatedRecord ok = result.getRecords().get(0);
        assertTrue(ok.isValid());
        assertEquals("NEW", ok.get(byName));
        assertEquals(Status.PAID, ok.get(byClass));
        final ValidatedRecord bad = result.getRecords().get(1);
        assertEquals(1, bad.getErrors().size());
        assertEquals(Kind.INVALID_ENUM, bad.getErrors().get(0).getKind());
        assertEquals("UNKNOWN", bad.getErrors().get(0).getRawValue());
    }

    @Test
    void testInvalidInteger() throws IOException {
        final ColumnRule rule = ColumnRule.integer(0);
        final CsvValidator validator = CsvValidator.of(rule);
        final ValidationResult result = validator.validate(CSVParser.parse("42\n4.2\n9999999999999999999999\n", CSVFormat.DEFAULT));
        assertEquals(Long.valueOf(42), result.getRecords().get(0).getLong(rule));
        assertEquals(Kind.INVALID_INTEGER, result.getRecords().get(1).getErrors().get(0).getKind());
        assertEquals(Kind.INVALID_INTEGER, result.getRecords().get(2).getErrors().get(0).getKind());
    }

    @Test
    void testMaxErrorsTruncates() throws IOException {
        final CsvValidator validator = CsvValidator.builder().rule(ColumnRule.integer(0)).maxErrors(2).build();
        final ValidationResult result = validator.validate(CSVParser.parse("a\nb\nc\nd\n", CSVFormat.DEFAULT));
        assertTrue(result.isTruncated());
        assertEquals(2, result.getErrorCount());
        assertEquals(2, result.getRecords().size());
        assertEquals(2, result.getInvalidRecords().size());
        assertTrue(result.hasErrors());
    }

    @Test
    void testErrorLimitReachedExactlyAtEndOfInputIsNotTruncated() throws IOException {
        final CsvValidator validator = CsvValidator.builder().rule(ColumnRule.integer(0)).maxErrors(2).build();
        final ValidationResult result = validator.validate(CSVParser.parse("a\nb\n", CSVFormat.DEFAULT));
        assertFalse(result.isTruncated());
        assertEquals(2, result.getErrorCount());
    }

    @Test
    void testMultipleErrorsInOneRowCountTowardLimit() throws IOException {
        final CsvValidator validator = CsvValidator.builder().rule(ColumnRule.integer(0)).rule(ColumnRule.decimal(1))
                .maxErrors(2).build();
        // One row with two errors already reaches the limit; the remaining row is unprocessed.
        final ValidationResult result = validator.validate(CSVParser.parse("x,y\n1,2\n", CSVFormat.DEFAULT));
        assertTrue(result.isTruncated());
        assertEquals(1, result.getRecords().size());
        assertEquals(2, result.getErrorCount());
    }

    @Test
    void testValidationContinuesAfterErrors() throws IOException {
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer(0));
        final ValidationResult result = validator.validate(CSVParser.parse("bad\n1\noops\n2\n", CSVFormat.DEFAULT));
        assertEquals(4, result.getRecords().size());
        assertFalse(result.isTruncated());
        assertEquals(2, result.getErrorCount());
        assertTrue(result.getRecords().get(1).isValid());
        assertTrue(result.getRecords().get(3).isValid());
    }

    @Test
    void testGetWithUnknownRuleFails() throws IOException {
        final CsvValidator validator = CsvValidator.of(ColumnRule.integer(0));
        final ValidationResult result = validator.validate(CSVParser.parse("1\n", CSVFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> result.getRecords().get(0).get(ColumnRule.integer(1)));
    }

    @Test
    void testBuilderValidation() {
        assertThrows(IllegalStateException.class, () -> CsvValidator.builder().build());
        assertThrows(IllegalArgumentException.class, () -> CsvValidator.builder().maxErrors(0));
        assertThrows(IllegalArgumentException.class, () -> ColumnRule.enumeration(0));
    }
}
