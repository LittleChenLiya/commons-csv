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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link FormulaGuard} and the formula protection policy.
 */
class FormulaGuardTest {

    private static CSVFormat excelProtect() {
        return CSVFormat.EXCEL.builder().setFormulaProtection(FormulaProtection.ON).get();
    }

    @Test
    void textEquals1Plus1IsProtected() {
        assertEquals("'=1+1", FormulaGuard.protectText("=1+1"));
    }

    @Test
    void textPlusMinusAtAreProtected() {
        assertEquals("'+1+1", FormulaGuard.protectText("+1+1"));
        assertEquals("'-1+1", FormulaGuard.protectText("-1+1"));
        assertEquals("'@1+1", FormulaGuard.protectText("@1+1"));
    }

    @Test
    void textWithLeadingWhitespaceIsProtected() {
        assertEquals("'  =1+1", FormulaGuard.protectText("  =1+1"));
        assertEquals("'\t=1+1", FormulaGuard.protectText("\t=1+1"));
        assertEquals("'\t@1+1", FormulaGuard.protectText("\t@1+1"));
        assertEquals("' \t @1+1", FormulaGuard.protectText(" \t @1+1"));
    }

    @Test
    void textWithoutFormulaIntroducerIsUnchanged() {
        assertEquals("hello", FormulaGuard.protectText("hello"));
        assertEquals("1+1", FormulaGuard.protectText("1+1"));
        assertEquals("abc", FormulaGuard.protectText("abc"));
    }

    @Test
    void emptyStringIsUnchanged() {
        assertEquals("", FormulaGuard.protectText(""));
        assertEquals("", FormulaGuard.INSTANCE.apply(""));
    }

    @Test
    void singleQuoteOriginalIsNotDoubled() {
        assertEquals("'=1+1", FormulaGuard.protectText("'=1+1"));
        assertEquals("'hello", FormulaGuard.protectText("'hello"));
        assertEquals("''", FormulaGuard.protectText("''"));
        assertEquals("'", FormulaGuard.protectText("'"));
    }

    @Test
    void unicodeIsHandled() {
        // Unicode text without a formula introducer is unchanged.
        assertEquals("café", FormulaGuard.protectText("café"));
        // A formula introducer after a non-breaking space is still detected.
        assertEquals("' =1+1", FormulaGuard.protectText(" =1+1"));
        // A formula introducer inside Unicode text is protected.
        assertEquals("'=1+1中文", FormulaGuard.protectText("=1+1中文"));
    }

    @Test
    void explicitNumberIsRetained() {
        assertEquals("-12.5", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("-12.5")));
        assertEquals("12.5", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("12.5")));
        assertEquals("-12", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("-12")));
        assertEquals("+12.5", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("+12.5")));
        assertEquals(".5", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric(".5")));
        assertEquals("5.", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("5.")));
        assertEquals("1e10", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("1e10")));
        assertEquals("-1E-10", FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("-1E-10")));
    }

    @Test
    void explicitNumberWithFormulaSyntaxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("1+2")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("=1+1")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("12.5.6")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("abc")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric(" 12")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("NaN")));
        assertThrows(IllegalArgumentException.class, () -> FormulaGuard.INSTANCE.apply(FormulaGuard.numeric("Infinity")));
    }

    @Test
    void numberObjectIsPassedThrough() {
        assertEquals(Integer.valueOf(12), FormulaGuard.INSTANCE.apply(Integer.valueOf(12)));
        assertEquals(Double.valueOf(12.5), FormulaGuard.INSTANCE.apply(Double.valueOf(12.5)));
    }

    @Test
    void nullIsPassedThrough() {
        assertNull(FormulaGuard.INSTANCE.apply(null));
    }

    @Test
    void originalValueIsNotModified() {
        final String original = "=1+1";
        FormulaGuard.INSTANCE.apply(original);
        assertEquals("=1+1", original);
    }

    @Test
    void csvEscapingIsStillAppliedAfterProtection() throws Exception {
        // The comma in the original value forces CSV quoting, applied after the prefix.
        final StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, excelProtect())) {
            printer.printRecord("=1,2");
        }
        assertEquals("\"'=1,2\"\r\n", out.toString());
    }

    @Test
    void printAndReadBackConfirmsPrefix() throws Exception {
        final StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, excelProtect())) {
            printer.printRecord("=1+1", FormulaGuard.numeric("-12.5"), "\t@1+1");
        }
        final String csv = out.toString();
        // Read back with the original CSV parser and confirm the prefix is present.
        try (CSVParser parser = CSVParser.parse(new StringReader(csv), CSVFormat.EXCEL)) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(1, records.size());
            final CSVRecord record = records.get(0);
            assertEquals("'=1+1", record.get(0));
            assertEquals("-12.5", record.get(1));
            assertEquals("'\t@1+1", record.get(2));
        }
    }

    @Test
    void offByDefaultLeavesPrintingUnchanged() throws Exception {
        final StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.EXCEL)) {
            printer.printRecord("=1+1", "-12.5", "@cmd");
        }
        // No prefix, no quoting: the original behavior is preserved.
        assertEquals("=1+1,-12.5,@cmd\r\n", out.toString());
    }

    @Test
    void realOutputExample() throws Exception {
        final StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, excelProtect())) {
            printer.printRecord("=1+1", FormulaGuard.numeric("-12.5"), "@cmd", "hello");
        }
        final String csv = out.toString();
        // Show the real output.
        System.out.println(csv);
        assertEquals("'=1+1,-12.5,'@cmd,hello\r\n", csv);
    }

    @Test
    void policyIsExplicitlyEnabled() {
        final CSVFormat off = CSVFormat.EXCEL;
        assertEquals(FormulaProtection.OFF, off.getFormulaProtection());
        assertTrue(!off.isFormulaProtectionEnabled());
        final CSVFormat on = excelProtect();
        assertEquals(FormulaProtection.ON, on.getFormulaProtection());
        assertTrue(on.isFormulaProtectionEnabled());
    }
}
