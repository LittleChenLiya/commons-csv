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

/**
 * The formula protection policy applied when printing CSV fields.
 *
 * <p>
 * Spreadsheet software such as Microsoft Excel, LibreOffice Calc, and Google Sheets treats any cell whose text starts with one of the formula introducers
 * {@code =}, {@code +}, {@code -}, or {@code @} as a formula. A cell that was meant to be read as plain text may then be evaluated, which can trigger
 * unintended actions (for example {@code =cmd|'/c calc'!A1} in Excel) or simply fail to parse. This is commonly called <em>CSV injection</em> or
 * <em>formula injection</em>.
 * </p>
 *
 * <p>
 * Because the risk only exists when a CSV file is handed to spreadsheet software, formula protection is <strong>opt-in</strong>: it has to be enabled
 * explicitly. The default {@link #OFF} leaves every printed byte identical to previous releases. When set to {@link #ON}, the printer runs every field
 * through {@link FormulaGuard} before applying the usual CSV quoting and escaping:
 * </p>
 * <ul>
 * <li>Text fields whose first non-whitespace character is a formula introducer are prefixed with a single quote {@code '}. Spreadsheet software ignores a
 * leading single quote and treats the cell as text. The original field value is never modified; the prefixed copy is what gets printed.</li>
 * <li>Fields explicitly declared as numbers through {@link FormulaGuard#numeric(CharSequence)} are checked against a strict number syntax. Legal numbers
 * such as {@code -12.5} pass through unchanged, while anything that is not a strict number (for example {@code 1+2}) is rejected with an
 * {@link IllegalArgumentException} rather than printed.</li>
 * </ul>
 *
 * <p>
 * CSV double quotes are <strong>not</strong> used as a protection measure: quoting a field does not stop spreadsheet software from evaluating its contents
 * as a formula. The single-quote prefix is added before the normal CSV formatting, so the original {@link CSVFormat} still decides how the result is quoted
 * and escaped.
 * </p>
 *
 * <p>
 * Usage:
 * </p>
 *
 * <pre>
 * CSVFormat format = CSVFormat.EXCEL.builder()
 *         .setFormulaProtection(FormulaProtection.ON)
 *         .get();
 * try (CSVPrinter printer = format.printer()) {
 *     printer.printRecord("=1+1", FormulaGuard.numeric("-12.5"), "@cmd");
 * }
 * </pre>
 *
 * <p>
 * prints:
 * </p>
 *
 * <pre>
 * '=1+1,-12.5,'@cmd
 * </pre>
 *
 * @see FormulaGuard
 * @since 1.15.0
 */
public enum FormulaProtection {

    /**
     * Formula protection is disabled. Fields are printed exactly as given; this is the default and preserves the behavior of previous releases.
     */
    OFF,

    /**
     * Formula protection is enabled. Text fields starting with a formula introducer are prefixed with a single quote, and fields declared as numbers are
     * validated against a strict number syntax before printing.
     */
    ON
}
