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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link FormulaProtectionConverter}.
 */
class FormulaProtectionConverterTest {

    private final FormulaProtectionConverter converter = new FormulaProtectionConverter();

    private String print(final FormulaProtectionConverter c, final Object... values) throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
            for (final Object value : values) {
                c.print(printer, value);
            }
            printer.println();
        }
        return sw.toString();
    }

    private List<CSVRecord> parse(final String csv) throws IOException {
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(csv))) {
            return parser.getRecords();
        }
    }

    @Test
    void testTextFormulaIsPrefixed() {
        assertEquals("'=1+1", converter.convertText("=1+1"));
        assertEquals("'+1", converter.convertText("+1"));
        assertEquals("'@cmd", converter.convertText("@cmd"));
        assertEquals("'-2+3", converter.convertText("-2+3"));
    }

    @Test
    void testLeadingWhitespaceIsIgnoredForDetectionButPreserved() {
        assertEquals("'  =1+1", converter.convertText("  =1+1"));
        assertEquals("'\t@cmd", converter.convertText("\t@cmd"));
        assertEquals("' \t -x", converter.convertText(" \t -x"));
    }

    @Test
    void testSafeTextIsUnchanged() {
        assertEquals("hello", converter.convertText("hello"));
        assertEquals("1+1", converter.convertText("1+1"));
        assertEquals("a=b", converter.convertText("a=b"));
        assertEquals(" leading space only", converter.convertText(" leading space only"));
    }

    @Test
    void testEmptyAndBlankAndNull() {
        assertEquals("", converter.convertText(""));
        assertEquals("   ", converter.convertText("   "));
        assertNull(converter.convertText(null));
        assertNull(converter.convert(null));
    }

    @Test
    void testSingleQuoteOriginalValues() {
        assertEquals("'", converter.convertText("'"));
        assertEquals("'=1", converter.convertText("'=1"));
        assertEquals("''+", converter.convertText("''+"));
        assertEquals("'already protected'", converter.convertText("'already protected'"));
    }

    @Test
    void testUnicode() {
        assertEquals("'=héllo中文", converter.convertText("=héllo中文"));
        assertEquals("中文", converter.convertText("中文"));
        assertEquals("émoji 🎉", converter.convertText("émoji 🎉"));
        // Full-width equals sign (U+FF1D) is not an ASCII formula trigger and stays unchanged.
        assertEquals("＝1+1", converter.convertText("＝1+1"));
        // Unicode text behind a dangerous ASCII trigger is still protected.
        assertEquals("'@中文", converter.convertText("@中文"));
    }

    @Test
    void testStrictNumbersAccepted() {
        assertEquals("-12.5", converter.convertNumber("-12.5"));
        assertEquals("0", converter.convertNumber("0"));
        assertEquals("+3", converter.convertNumber("+3"));
        assertEquals(".5", converter.convertNumber(".5"));
        assertEquals("12.", converter.convertNumber("12."));
        assertEquals("1e5", converter.convertNumber("1e5"));
        assertEquals("-1.5E-3", converter.convertNumber("-1.5E-3"));
    }

    @Test
    void testStrictNumbersRejected() {
        // A numeric declaration is never trusted blindly.
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("1+2"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("=1+2"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber(""));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber(" 1"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("1 "));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("1,000"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("0x10"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("NaN"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("+"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("."));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber("1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> converter.convertNumber(null));
    }

    @Test
    void testConvertObject() {
        assertEquals("12.5", converter.convert(12.5));
        assertEquals("-3", converter.convert(-3));
        assertEquals("'=x", converter.convert("=x"));
        // Non-finite doubles fall back to text protection instead of failing.
        assertEquals("NaN", converter.convert(Double.NaN));
        assertEquals("'-Infinity", converter.convert(Double.NEGATIVE_INFINITY));
    }

    @Test
    void testPrintAndParseRoundTrip() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
            converter.print(printer, "=1+1");
            converter.printNumber(printer, "-12.5");
            converter.print(printer, "\t@cmd");
            printer.println();
        }
        final String csv = sw.toString();
        final List<CSVRecord> records = parse(csv);
        assertEquals(1, records.size());
        final CSVRecord record = records.get(0);
        assertEquals("'=1+1", record.get(0));
        assertEquals("-12.5", record.get(1));
        assertEquals("'\t@cmd", record.get(2));
    }

    @Test
    void testPrintNumberRejectsExpression() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
            assertThrows(IllegalArgumentException.class, () -> converter.printNumber(printer, "1+2"));
        }
    }

    @Test
    void testCsvEscapingStillAppliesAfterConversion() throws IOException {
        // The converted value contains a comma and quotes: the original CSVFormat escaping must still apply.
        final String csv = print(converter, "=\"a,b\"");
        final List<CSVRecord> records = parse(csv);
        assertEquals("'=\"a,b\"", records.get(0).get(0));
    }

    @Test
    void testPrintRecordVarargsAndIterable() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
            converter.printRecord(printer, "=1+1", "safe", 42);
            converter.printRecord(printer, Arrays.asList("@x", null, ""));
        }
        final List<CSVRecord> records = parse(sw.toString());
        assertEquals(Arrays.asList("'=1+1", "safe", "42"), records.get(0).toList());
        assertEquals(Arrays.asList("'@x", "", ""), records.get(1).toList());
    }

    @Test
    void testLegacyPrinterBehaviorUnchanged() throws IOException {
        // Without the converter, CSVPrinter prints values exactly as before (opt-in only).
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
            printer.print("=1+1");
            printer.print("@cmd");
            printer.println();
        }
        assertEquals("=1+1,@cmd\r\n", sw.toString());
    }
}
