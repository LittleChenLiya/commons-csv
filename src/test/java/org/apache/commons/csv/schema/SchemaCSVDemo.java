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

import java.io.IOException;
import java.io.StringReader;

import org.apache.commons.csv.CSVFormat;

/**
 * Demonstrates {@link SchemaCSVReader}: prints each data record with its section number,
 * record number, and field values bound to the section's header.
 *
 * <p>
 * Run with:
 * </p>
 *
 * <pre>
 * mvn -o test-compile exec:java -Dexec.mainClass=org.apache.commons.csv.schema.SchemaCSVDemo
 * </pre>
 *
 * <p>
 * or directly from the compiled test classes.
 * </p>
 */
public final class SchemaCSVDemo {

    private SchemaCSVDemo() {
        // no instances
    }

    /**
     * Runs the demo.
     *
     * @param args ignored.
     * @throws IOException if an I/O error occurs.
     */
    public static void main(final String[] args) throws IOException {
        final String csv = "__schema__,name,age\n"
                + "a,1\n"
                + "__schema__,age,name\n"
                + "2,b\n";
        System.out.println("Input CSV:");
        System.out.println(csv);
        System.out.println("Parsed records (section, record, fields):");
        try (SchemaCSVReader reader = new SchemaCSVReader(new StringReader(csv), CSVFormat.DEFAULT)) {
            for (final SchemaRecord record : reader) {
                System.out.printf("  section %d, record %d: %s%n",
                        Integer.valueOf(record.getSectionNumber()),
                        Long.valueOf(record.getRecordNumber()),
                        record.toMap());
            }
            if (reader.isTerminated()) {
                System.out.println("  reader terminated: " + reader.getTerminalError().getMessage());
            }
        }
    }
}
