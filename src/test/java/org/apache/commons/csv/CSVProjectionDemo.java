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
import java.nio.charset.StandardCharsets;

import org.apache.commons.csv.CSVProjection.MissingPolicy;

/**
 * Demonstrates {@link CSVProjection}: selects columns by header name and occurrence, aliases outputs, and prints the
 * projected values of three records together with their source positions.
 */
public final class CSVProjectionDemo {

    public static void main(final String[] args) throws Exception {
        final String csv = "id,name,name\n1,A,B\n2,C\n3,\"x\ny\",D\n";
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().get();
        try (CSVParser parser = CSVParser.builder().setReader(new StringReader(csv)).setFormat(format)
                .setCharset(StandardCharsets.UTF_8).setTrackBytes(true).get()) {
            final CSVProjection projection = CSVProjection.builder()
                    .column("id")
                    .column("name", 0, "firstName")
                    .column("name", 1, "secondName")
                    .onMissingColumn(MissingPolicy.DEFAULT, "<missing>")
                    .compile(parser);
            parser.stream().map(projection::project).forEach(record -> System.out.printf(
                    "recordNumber=%d charPos=%d bytePos=%d -> %s%n",
                    record.getRecordNumber(), record.getCharacterPosition(), record.getBytePosition(), record.toMap()));
        }
    }

    private CSVProjectionDemo() {
    }
}
