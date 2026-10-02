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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests the prepared-record printing entry point
 * {@link CSVPrinter#printPreparedRecord(Iterable, CSVConverter)}: columns are converted and
 * escaped before anything reaches the target writer, conversion failures are reported
 * separately from output I/O failures, and the writer stays owned by the caller.
 */
class CSVPrinterPreparedRecordTest {

    /** A closeable writer that records whether the caller (not the printer) closed it. */
    private static final class CloseTrackingWriter extends StringWriter {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    /** An Appendable that accepts a bounded number of chars and then fails, like a broken target stream. */
    private static final class FailingAppendable implements Appendable {
        private final StringBuilder received = new StringBuilder();
        private final int charsToAccept;

        FailingAppendable(final int charsToAccept) {
            this.charsToAccept = charsToAccept;
        }

        @Override
        public Appendable append(final char c) throws IOException {
            return append(String.valueOf(c));
        }

        @Override
        public Appendable append(final CharSequence csq) throws IOException {
            return append(csq, 0, csq.length());
        }

        @Override
        public Appendable append(final CharSequence csq, final int start, final int end) throws IOException {
            // Simulate a target that dies mid-record: some bytes land, then the write fails.
            final int accepted = Math.min(end - start, charsToAccept - received.length());
            if (accepted > 0) {
                received.append(csq, start, start + accepted);
            }
            throw new IOException("simulated output failure");
        }
    }

    @Test
    void testCloseOwnershipStaysWithCaller() throws IOException {
        final CloseTrackingWriter writer = new CloseTrackingWriter();
        final CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
        printer.printPreparedRecord(Arrays.asList("a", "b", "c"), value -> value);
        // Printing a prepared record must not close or take over the writer owned by the caller.
        assertFalse(writer.closed, "printer must not close the writer owned by the caller");
        // The caller still owns the writer and can keep using it.
        writer.append("# caller still owns this writer");
        writer.close();
        assertTrue(writer.closed, "caller closes the writer");
        assertTrue(writer.toString().contains("# caller still owns this writer"));
    }

    @Test
    void testConversionFailureOnThirdColumnLeavesWriterEmpty() throws IOException {
        final StringWriter sw = new StringWriter();
        final CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT);
        final List<String> values = Arrays.asList("first", "second", "boom");
        final CSVConverter<String> converter = value -> {
            if ("boom".equals(value)) {
                throw new IllegalArgumentException("cannot convert: " + value);
            }
            return value;
        };
        final CSVConversionException thrown = assertThrows(CSVConversionException.class,
            () -> printer.printPreparedRecord(values, converter));
        assertEquals(2, thrown.getColumnIndex(), "zero-based index of the failing column");
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        // Acceptance: the half-prepared record never reaches the writer.
        assertEquals(0, sw.getBuffer().length(), "no partial record may be written");
        assertEquals(0, sw.toString().length());
        assertEquals(0, printer.getRecordCount(), "failed record must not be counted");
    }

    @Test
    void testConversionFailureIsNotAnIoFailure() throws IOException {
        final StringWriter sw = new StringWriter();
        final CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT);
        final CSVConversionException thrown = assertThrows(CSVConversionException.class,
            () -> printer.printPreparedRecord(Arrays.asList("a", "b"), value -> {
                throw new IllegalStateException("bad column");
            }));
        // Distinct types: conversion failure is an unchecked CSVConversionException, while only a
        // failing target output surfaces as a checked IOException (see testWriterFailureIsDistinctAndNotCounted).
        assertInstanceOf(RuntimeException.class, thrown);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void testConverterInvokedAtMostOncePerColumn() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final AtomicInteger calls = new AtomicInteger();
            final List<String> values = Arrays.asList("x", "y", "z");
            printer.printPreparedRecord(values, value -> {
                calls.incrementAndGet();
                return value;
            });
            assertEquals(values.size(), calls.get(), "converter must run exactly once per column, no length pre-pass");
            assertEquals(1, printer.getRecordCount());
        }
    }

    @Test
    void testPreparedRecordContinuesPendingRecord() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            printer.print("a");
            printer.printPreparedRecord(Arrays.asList("b", "c"));
            assertEquals("a,b,c" + CSVFormat.DEFAULT.getRecordSeparator(), sw.toString());
            assertEquals(1, printer.getRecordCount());
        }
    }

    @Test
    void testPrintLengthDemoAndRoundTrip() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final List<String> record = Arrays.asList("a,b", "line1\nline2", "plain");
            final int lengthBefore = sw.getBuffer().length();
            printer.printPreparedRecord(record, value -> value);
            final int lengthAfter = sw.getBuffer().length();
            // Short demo: the writer grows exactly once, by the fully prepared record.
            System.out.println("[prepared-record demo] writer length before=" + lengthBefore + ", after=" + lengthAfter);
            assertEquals(0, lengthBefore);
            assertTrue(lengthAfter > 0);
            assertEquals(1, printer.getRecordCount());
        }
        // Acceptance: a record containing a comma and a newline parses back to the same three columns.
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(sw.toString()))) {
            final List<CSVRecord> records = parser.getRecords();
            assertEquals(1, records.size());
            assertEquals(Arrays.asList("a,b", "line1\nline2", "plain"), records.get(0).toList());
        }
    }

    @Test
    void testRecordCountIncrementsOnlyOnSuccess() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            printer.printPreparedRecord(Arrays.asList("ok1"));
            printer.printPreparedRecord(Arrays.asList("ok2"));
            assertEquals(2, printer.getRecordCount());
            assertThrows(CSVConversionException.class, () -> printer.printPreparedRecord(Arrays.asList("bad"), value -> {
                throw new IllegalStateException("bad column");
            }));
            assertEquals(2, printer.getRecordCount(), "conversion failure must not increment the record count");
            printer.printPreparedRecord(Arrays.asList("ok3"));
            assertEquals(3, printer.getRecordCount());
        }
    }

    @Test
    void testWriterFailureIsDistinctAndNotCounted() throws IOException {
        final FailingAppendable appendable = new FailingAppendable(3);
        final CSVPrinter printer = new CSVPrinter(appendable, CSVFormat.DEFAULT);
        // Conversion succeeds; the target output fails while the prepared record is written.
        final IOException thrown = assertThrows(IOException.class,
            () -> printer.printPreparedRecord(Arrays.asList("aaa", "bbb", "ccc"), value -> value));
        assertEquals("simulated output failure", thrown.getMessage());
        assertEquals(0, printer.getRecordCount(), "output failure must not increment the record count");
        // Unlike a conversion failure, an output failure does NOT roll back already-written bytes.
        assertEquals(3, appendable.received.length(), "partial bytes stay in the target; no rollback is promised");
        assertEquals("aaa", appendable.received.toString());
    }
}
