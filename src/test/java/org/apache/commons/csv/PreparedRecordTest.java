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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CSVPrinter.PreparedRecord} and the prepared-record print entry.
 */
class PreparedRecordTest {

    private static final String CRLF = CSVFormat.DEFAULT.getRecordSeparator();

    private static final List<String> THREE_COLUMNS = Arrays.asList("a,b", "x\ny", "z");

    /**
     * A converter that fails on the third column.
     */
    private static Function<Object, String> failOnThird() {
        return new Function<Object, String>() {
            private int count;

            @Override
            public String apply(final Object value) {
                count++;
                if (count == 3) {
                    throw new IllegalStateException("converter boom");
                }
                return String.valueOf(value);
            }
        };
    }

    /**
     * A writer that writes the first half of the first append and then throws, simulating an I/O failure after
     * some bytes have been handed to the output.
     */
    private static Writer partialWriter(final StringWriter out) {
        return new Writer() {
            private boolean failed;

            @Override
            public void close() throws IOException {
                out.close();
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }

            @Override
            public void write(final char[] cbuf, final int off, final int len) throws IOException {
                if (failed) {
                    throw new IOException("writer already failed");
                }
                failed = true;
                out.write(cbuf, off, Math.max(0, len / 2));
                throw new IOException("io boom");
            }
        };
    }

    @Test
    void testConversionFailureAndIoFailureAreDistinct() throws IOException {
        // A converter failure happens while preparing: nothing is ever handed to the output.
        final StringWriter conversionOut = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(conversionOut, CSVFormat.DEFAULT)) {
            assertThrows(IllegalStateException.class, () -> printer.prepareRecord(THREE_COLUMNS, failOnThird()));
            assertEquals(0, conversionOut.getBuffer().length());
            assertEquals(0, printer.getRecordCount());
        }
        // An I/O failure happens while printing: bytes may already have been written and cannot be rolled back.
        final StringWriter ioOut = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(partialWriter(ioOut), CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            assertThrows(IOException.class, () -> printer.printRecord(record));
            // We do not promise rollback: partial bytes may remain in the output.
            assertTrue(ioOut.getBuffer().length() > 0);
            assertEquals(0, printer.getRecordCount());
        }
    }

    @Test
    void testConverterCalledOncePerColumn() throws IOException {
        final StringWriter sw = new StringWriter();
        final AtomicInteger calls = new AtomicInteger();
        final Function<Object, String> counting = value -> {
            calls.incrementAndGet();
            return String.valueOf(value);
        };
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, counting);
            // The converter ran exactly once per column while preparing...
            assertEquals(3, calls.get());
            // ...and is never called again when printing (no measure-then-print double execution).
            printer.printRecord(record);
            assertEquals(3, calls.get());
            printer.printRecord(record);
            assertEquals(3, calls.get());
            assertEquals(2, printer.getRecordCount());
        }
    }

    @Test
    void testLengthBeforeAndAfterPrint() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            final int before = sw.getBuffer().length();
            printer.printRecord(record);
            final int after = sw.getBuffer().length();
            System.out.printf("Prepared record demo: text length=%d, writer length before=%d after=%d%n", record.getText().length(), before, after);
            assertEquals(0, before);
            assertTrue(after > 0);
            assertEquals(record.getText().length(), after);
        }
    }

    @Test
    void testPrepareDoesNotIncrementCount() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            assertEquals(0, printer.getRecordCount());
            assertTrue(record.getText().length() > 0);
        }
    }

    @Test
    void testPrepareNullArgumentsThrow() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            assertThrows(NullPointerException.class, () -> printer.prepareRecord(null, String::valueOf));
            assertThrows(NullPointerException.class, () -> printer.prepareRecord(THREE_COLUMNS, null));
        }
    }

    @Test
    void testPreparedRecordTextIsFullyEscaped() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            // The comma and the line break are quoted; values are separated by the delimiter; the record ends with the separator.
            assertEquals("\"a,b\",\"x\ny\",z" + CRLF, record.getText());
        }
    }

    @Test
    void testPrintNullThrows() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            assertThrows(NullPointerException.class, () -> printer.printRecord((CSVPrinter.PreparedRecord) null));
        }
    }

    @Test
    void testPrintPreparedRecordDoesNotCloseWriter() throws IOException {
        @SuppressWarnings("resource")
        final Writer writer = mock(Writer.class);
        final CSVPrinter.PreparedRecord record;
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            printer.printRecord(record);
            // Printing a prepared record does not close the writer; the writer stays with the caller.
            verify(writer, never()).close();
            assertEquals(1, printer.getRecordCount());
        }
        // The old close behavior is unchanged: closing the printer closes the underlying writer.
        verify(writer).close();
    }

    @Test
    void testPrintPreparedRecordIncrementsCount() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            for (int i = 1; i <= 3; i++) {
                printer.printRecord(record);
                assertEquals(i, printer.getRecordCount());
            }
        }
    }

    @Test
    void testPrintPreparedRecordRoundTripsCommaAndNewLine() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(THREE_COLUMNS, String::valueOf);
            printer.printRecord(record);
        }
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(sw.toString()))) {
            final CSVRecord csvRecord = parser.getRecords().get(0);
            assertArrayEquals(new String[] { "a,b", "x\ny", "z" }, csvRecord.values());
        }
    }

    @Test
    void testVarargsPrepare() throws IOException {
        final StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            final CSVPrinter.PreparedRecord record = printer.prepareRecord(String::valueOf, "a,b", "x\ny", "z");
            printer.printRecord(record);
            assertEquals(1, printer.getRecordCount());
        }
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(sw.toString()))) {
            final CSVRecord csvRecord = parser.getRecords().get(0);
            assertArrayEquals(new String[] { "a,b", "x\ny", "z" }, csvRecord.values());
        }
    }
}
