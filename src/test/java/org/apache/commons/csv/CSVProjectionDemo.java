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

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Demonstrates {@link CSVProjection}: compiles a projection once from the header, then applies it record by record
 * while streaming, printing the projected values together with the source record number and character/byte positions.
 *
 * <p>
 * Covers three record shapes: a complete record, a record missing a column (filled from a fixed default), and a record
 * with an empty value (preserved as-is).
 * </p>
 */
public final class CSVProjectionDemo {

    public static void main(final String[] args) throws Exception {
        final String input = "id,name,name,comment\n"
                + "1,Alice,Smith,\"line1\nline2\"\n"
                + "2,Bob,Jones\n"
                + "3,Carol,,\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().get();
        try (CSVParser parser = CSVParser.builder().setReader(new InputStreamReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)).setCharset(StandardCharsets.UTF_8).setFormat(format).setTrackBytes(true).get()) {
            // Compile once from the header; no data record is consumed here.
            final CSVProjection projection = CSVProjection.builder()
                    .select("id")
                    .select("name", 1, "lastName")
                    .select("comment")
                    .onMissingColumn(CSVProjection.MissingColumnPolicy.DEFAULT, "<missing>")
                    .compile(parser);
            System.out.println("aliases: " + projection.getAliases());
            // Stream record by record; the whole file is never loaded into memory.
            for (final CSVRecord record : parser) {
                final ProjectedRecord projected = projection.project(record);
                System.out.printf("recordNumber=%d charPos=%d bytePos=%d -> %s%n",
                        projected.getRecordNumber(), projected.getCharacterPosition(), projected.getBytePosition(),
                        projected.toMap());
            }
        }
    }

    private CSVProjectionDemo() {
    }
}
