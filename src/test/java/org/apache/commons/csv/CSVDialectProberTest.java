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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVDialectProber}.
 */
class CSVDialectProberTest {

    /** A reader that supports neither mark/reset nor being consumed twice. */
    private static final class ReadOnceReader extends Reader {

        private final Reader delegate;
        private boolean closed;

        ReadOnceReader(final String input) {
            this.delegate = new StringReader(input);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }

        @Override
        public void mark(final int readAheadLimit) throws IOException {
            throw new IOException("mark not supported");
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) throws IOException {
            if (closed) {
                throw new IOException("reader is closed");
            }
            return delegate.read(cbuf, off, len);
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("reset not supported");
        }
    }

    private static final CSVFormat COMMA = CSVFormat.DEFAULT.builder().setDelimiter(',').setQuote('"').setEscape('\\').get();

    private static final CSVFormat SEMICOLON = CSVFormat.DEFAULT.builder().setDelimiter(';').setQuote('"').setEscape('\\').get();

    private static String readAll(final Reader reader) throws IOException {
        final StringBuilder builder = new StringBuilder();
        final char[] buffer = new char[7]; // deliberately odd size to exercise partial replays
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) != -1) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    @Test
    void testDetectsSemicolonDialectWithQuotedCommas() throws IOException {
        final String input = "name;note\r\n\"Alice\";\"x,y\"\r\n\"Bob\";\"p,q\"\r\n";
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(input), 4096, COMMA, SEMICOLON);
        assertFalse(detection.isAmbiguous());
        assertEquals(SEMICOLON, detection.getFormat());
        assertTrue(detection.getScore(SEMICOLON) > detection.getScore(COMMA));
        assertEquals(input, readAll(detection.getReader()));
    }

    @Test
    void testDetectsSemicolonDialectRegardlessOfCandidateOrder() throws IOException {
        final String input = "a;b\n1;2\n3;4\n";
        final CSVDialectProber.Detection first = CSVDialectProber.detect(new ReadOnceReader(input), 4096, COMMA, SEMICOLON);
        final CSVDialectProber.Detection second = CSVDialectProber.detect(new ReadOnceReader(input), 4096, SEMICOLON, COMMA);
        assertEquals(SEMICOLON, first.getFormat());
        assertEquals(SEMICOLON, second.getFormat());
    }

    @Test
    void testEmptyPrefixIsAmbiguous() throws IOException {
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader("a;b\n"), 0, COMMA, SEMICOLON);
        assertTrue(detection.isAmbiguous());
        assertEquals("a;b\n", readAll(detection.getReader()));
    }

    @Test
    void testHandlesChineseCharacters() throws IOException {
        final String input = "姓名;备注\r\n\"张三\";\"北京,上海\"\r\n\"李四\";\"广州,深圳\"\r\n";
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(input), 4096, COMMA, SEMICOLON);
        assertFalse(detection.isAmbiguous());
        assertEquals(SEMICOLON, detection.getFormat());
        assertEquals(input, readAll(detection.getReader()));
    }

    @Test
    void testHandlesCrlfSplitAtPrefixBoundary() throws IOException {
        final String input = "a;b\r\n1;2\r\n3;4\r\n";
        final int splitAfterCr = input.indexOf('\r') + 1;
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(input), splitAfterCr, COMMA, SEMICOLON);
        assertTrue(detection.isTruncated());
        assertEquals(SEMICOLON, detection.getFormat());
        assertEquals(input, readAll(detection.getReader()));
    }

    @Test
    void testHandlesVeryLongFirstRecord() throws IOException {
        final StringBuilder input = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            input.append('x');
        }
        final String tail = ";tail\n1;2\n";
        input.append(tail);
        final String sample = input.toString();
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(sample), 1024, COMMA, SEMICOLON);
        // The prefix ends inside the first record: no complete records, no structural evidence.
        assertTrue(detection.isTruncated());
        assertTrue(detection.isAmbiguous());
        assertEquals(sample, readAll(detection.getReader()));
    }

    @Test
    void testPropagatesReaderFailure() {
        final Reader broken = new Reader() {
            @Override
            public void close() {
                // no-op
            }

            @Override
            public int read(final char[] cbuf, final int off, final int len) throws IOException {
                throw new IOException("simulated failure");
            }
        };
        assertThrows(IOException.class, () -> CSVDialectProber.detect(broken, 128, COMMA, SEMICOLON));
    }

    @Test
    void testRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> CSVDialectProber.detect(new StringReader(""), -1, COMMA));
        assertThrows(IllegalArgumentException.class, () -> CSVDialectProber.detect(new StringReader(""), 10));
        assertThrows(NullPointerException.class, () -> CSVDialectProber.detect(null, 10, COMMA));
    }

    @Test
    void testReplaysFullInputFromReadOnceSource() throws IOException {
        final StringBuilder builder = new StringBuilder("a;b\n1;2\n");
        for (int i = 0; i < 100; i++) {
            builder.append("z;y\n");
        }
        final String input = builder.toString();
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(input), 5, COMMA, SEMICOLON);
        assertEquals(SEMICOLON, detection.getFormat());
        assertEquals(input, readAll(detection.getReader()));
    }

    @Test
    void testReportsAmbiguityForSingleColumnSample() throws IOException {
        final String input = "alpha\nbeta\ngamma\n";
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(input), 4096, COMMA, SEMICOLON);
        assertTrue(detection.isAmbiguous());
        assertEquals(Arrays.asList(COMMA, SEMICOLON), detection.getBestCandidates());
        assertThrows(IllegalStateException.class, detection::getFormat);
        assertEquals(input, readAll(detection.getReader()));
    }

    @Test
    void testScoreOfUnknownCandidateRejected() throws IOException {
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader("a;b\n"), 4096, SEMICOLON);
        assertThrows(IllegalArgumentException.class, () -> detection.getScore(COMMA));
    }

    @Test
    void testTruncatedQuotedFieldIsNotParseFailure() throws IOException {
        // Prefix ends inside a quoted field: the partial record must not count as a parse failure.
        final String input = "a;b\n\"x;y\";\"1,2\"\n3;4\n";
        final int cutInsideQuote = input.indexOf("\"x;y") + 3;
        final CSVDialectProber.Detection detection = CSVDialectProber.detect(new ReadOnceReader(input), cutInsideQuote, COMMA, SEMICOLON);
        assertTrue(detection.isTruncated());
        assertEquals(SEMICOLON, detection.getFormat());
        assertEquals(input, readAll(detection.getReader()));
    }
}
