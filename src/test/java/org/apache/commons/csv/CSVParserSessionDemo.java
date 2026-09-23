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

import java.util.Arrays;
import java.util.List;

/**
 * Demonstrates {@link CSVParserSession}: CSV text is fed in small chunks over about 90 seconds and every record is printed as soon as it is complete,
 * instead of printing everything once at the end.
 * <p>
 * Run with an optional first argument: the total streaming duration in milliseconds (default 90000).
 * </p>
 */
public final class CSVParserSessionDemo {

    private static final int ROW_COUNT = 900;

    private static final int CHUNK_SIZE = 64;

    private static String buildCsv() {
        final StringBuilder builder = new StringBuilder(ROW_COUNT * 40);
        for (int i = 1; i <= ROW_COUNT; i++) {
            builder.append(i).append(",\"name \"\"").append(i).append("\"\"\",\"note ").append(i);
            if (i % 7 == 0) {
                builder.append(" with an embedded\r\nline break");
            }
            builder.append("\"\r\n");
        }
        return builder.toString();
    }

    public static void main(final String[] args) throws Exception {
        final long durationMillis = args.length > 0 ? Long.parseLong(args[0]) : 90_000L;
        final String csv = buildCsv();
        final int chunkCount = (csv.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        final long pauseMillis = Math.max(1, durationMillis / chunkCount);
        final long startMillis = System.currentTimeMillis();
        long recordCount = 0;
        System.out.printf("Streaming %,d characters in %,d chunks of %,d characters over about %,d ms...%n", csv.length(), chunkCount, CHUNK_SIZE,
                durationMillis);
        try (CSVParserSession session = CSVParserSession.create(CSVFormat.RFC4180)) {
            for (int pos = 0; pos < csv.length(); pos += CHUNK_SIZE) {
                final String chunk = csv.substring(pos, Math.min(csv.length(), pos + CHUNK_SIZE));
                recordCount += print(session.feed(chunk), startMillis);
                Thread.sleep(pauseMillis);
            }
            recordCount += print(session.finish(), startMillis);
            System.out.printf("Done after %,d ms: %,d records, record number %,d, line %,d, character position %,d.%n",
                    System.currentTimeMillis() - startMillis, recordCount, session.getRecordNumber(), session.getCurrentLineNumber(),
                    session.getCharacterPosition());
        }
    }

    private static long print(final List<CSVRecord> records, final long startMillis) {
        for (final CSVRecord record : records) {
            System.out.printf("[%,7d ms] record %,3d: %s%n", System.currentTimeMillis() - startMillis, record.getRecordNumber(),
                    Arrays.toString(record.values()));
        }
        return records.size();
    }

    private CSVParserSessionDemo() {
        // no instances
    }
}
