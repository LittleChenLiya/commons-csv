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

import java.io.StringReader;
import java.io.StringWriter;

/**
 * Short runnable example of {@link FormulaProtectionConverter}: prints the raw CSV output and the values read
 * back with the standard {@link CSVParser}.
 */
public final class FormulaProtectionExample {

    public static void main(final String[] args) throws Exception {
        final FormulaProtectionConverter converter = new FormulaProtectionConverter();
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
            converter.printRecord(printer, "name", "formula", "balance");
            converter.print(printer, "alice");
            converter.print(printer, "=1+1");          // dangerous text -> prefixed
            converter.printNumber(printer, "-12.5");    // declared number, strict syntax -> kept
            printer.println();
            converter.print(printer, "bob");
            converter.print(printer, "\t@cmd");         // tab then @ -> prefixed
            converter.printNumber(printer, "1.5E3");    // exponent notation -> kept
            printer.println();
        }
        final String csv = sw.toString();
        System.out.println("--- raw CSV output ---");
        System.out.print(csv);
        System.out.println("--- values parsed back with CSVParser ---");
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(csv))) {
            for (final CSVRecord record : parser) {
                System.out.println(record.toList());
            }
        }
        System.out.println("--- rejected numeric declaration ---");
        try {
            converter.convertNumber("1+2");
        } catch (final IllegalArgumentException e) {
            System.out.println("convertNumber(\"1+2\") threw: " + e.getMessage());
        }
    }

    private FormulaProtectionExample() {
        // no instances
    }
}
