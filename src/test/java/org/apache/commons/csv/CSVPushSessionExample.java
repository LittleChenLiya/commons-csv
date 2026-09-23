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

import java.util.List;

/**
 * Demonstrates push-style parsing: a slow network feed is simulated by delivering a CSV document in small chunks with a pause between chunks, and every
 * record is printed as soon as {@link CSVPushSession#feed(CharSequence)} produces it, instead of all at once at the end.
 *
 * <p>
 * With the default pause of 250 ms between the 360 chunks the run takes about 90 seconds. Pass a different pause in milliseconds as the first argument to
 * shorten or lengthen the run, for example {@code CSVPushSessionExample 0} for an immediate run.
 * </p>
 */
public final class CSVPushSessionExample {

    private static final int RECORD_COUNT = 360;

    private static final long DEFAULT_PAUSE_MILLIS = 250;

    private static String buildSampleCsv() {
        final StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= RECORD_COUNT; i++) {
            csv.append("row-").append(i).append(",\"").append("value ").append(i).append("\"\n");
        }
        return csv.toString();
    }

    /**
     * Runs the example.
     *
     * @param args optional pause between chunks in milliseconds, defaults to 250 so the run takes about 90 seconds.
     * @throws Exception on any error.
     */
    public static void main(final String[] args) throws Exception {
        final long pauseMillis = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_PAUSE_MILLIS;
        final String csv = buildSampleCsv();
        final int chunkSize = (int) Math.ceil(csv.length() / (double) RECORD_COUNT);
        final CSVPushSession session = new CSVPushSession(CSVFormat.DEFAULT);
        final long start = System.currentTimeMillis();
        long delivered = 0;
        for (int i = 0; i < csv.length(); i += chunkSize) {
            final String chunk = csv.substring(i, Math.min(csv.length(), i + chunkSize));
            delivered += print(session.feed(chunk), start);
            Thread.sleep(pauseMillis);
        }
        delivered += print(session.finish(), start);
        System.out.printf("Done: %,d records delivered in %.1f seconds.%n", Long.valueOf(delivered), (System.currentTimeMillis() - start) / 1000.0);
    }

    private static long print(final List<CSVRecord> records, final long start) {
        for (final CSVRecord record : records) {
            System.out.printf("[%6.1fs] record %3d: %s%n", (System.currentTimeMillis() - start) / 1000.0, Long.valueOf(record.getRecordNumber()),
                    record.toList());
        }
        return records.size();
    }

    private CSVPushSessionExample() {
        // no instances
    }
}

