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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import org.apache.commons.csv.CSVDialectSniffer.CandidateScore;
import org.apache.commons.csv.CSVDialectSniffer.SniffResult;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVDialectSniffer}.
 */
class CSVDialectSnifferTest {

    /** A reader that only supports forward reading and delivers input in small chunks. */
    private static final class ForwardOnlyReader extends Reader {

        private final String input;
        private final int chunkSize;
        private int pos;

        ForwardOnlyReader(final String input, final int chunkSize) {
            this.input = input;
            this.chunkSize = chunkSize;
        }

        @Override
        public void close() {
            // no resources
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) {
            if (pos >= input.length()) {
                return -1;
            }
            final int n = Math.min(Math.min(len, chunkSize), input.length() - pos);
            input.getChars(pos, pos + n, cbuf, off);
            pos += n;
            return n;
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("read-once input");
        }
    }

    private static final CSVFormat COMMA = CSVFormat.DEFAULT.builder().setDelimiter(',').setQuote('"').get();

    private static final CSVFormat SEMICOLON = CSVFormat.DEFAULT.builder().setDelimiter(';').setQuote('"').get();

    private static String readAll(final Reader reader) throws IOException {
        final StringBuilder sb = new StringBuilder();
        final char[] buf = new char[64];
        int n;
        while ((n = reader.read(buf)) >= 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String readAllOneCharAtATime(final Reader reader) throws IOException {
        final StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) >= 0) {
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static String repeat(final String s, final int times) {
        final StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    @Test
    void testAmbiguousWhenAllCandidatesInvalid() throws IOException {
        // A first record longer than the prefix: no candidate sees a complete record.
        final String input = "a1;" + repeat("x", 500) + "\n2;3\n";
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), 64, COMMA, SEMICOLON);
        assertTrue(result.isAmbiguous());
        assertEquals(2, result.getBestCandidates().size());
        assertThrows(IllegalStateException.class, result::getFormat);
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testCrlfSplitAtPrefixLimit() throws IOException {
        final String input = "h1;h2\r\n1;2\r\n3;4\r\n";
        // The prefix limit lands between the CR and LF of the second record separator.
        final int limit = input.indexOf('\r', input.indexOf("\r\n") + 2) + 1;
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), limit, COMMA, SEMICOLON);
        assertFalse(result.isAmbiguous());
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testEmptyCandidatesRejected() {
        assertThrows(IllegalArgumentException.class, () -> CSVDialectSniffer.sniff(new StringReader(""), 16));
    }

    @Test
    void testNonPositivePrefixLimitRejected() {
        assertThrows(IllegalArgumentException.class, () -> CSVDialectSniffer.sniff(new StringReader("a,b"), 0, COMMA));
    }

    @Test
    void testNullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> CSVDialectSniffer.sniff(null, 16, COMMA));
        assertThrows(NullPointerException.class, () -> CSVDialectSniffer.sniff(new StringReader(""), 16, (CSVFormat) null));
        assertThrows(NullPointerException.class, () -> CSVDialectSniffer.sniff(new StringReader(""), 16, (List<CSVFormat>) null));
    }

    @Test
    void testPrefixTruncatedInsideQuotedField() throws IOException {
        final String input = "id;comment\n1;\"abcdefgh\"\n2;\"ijklmnop\"\n";
        // Cut inside the quoted value of the second record: the incomplete record must not be a parse failure.
        final int limit = input.indexOf("cdef");
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), limit, COMMA, SEMICOLON);
        assertFalse(result.isAmbiguous());
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testReaderExceptionPropagates() {
        final Reader broken = new Reader() {
            private int reads;

            @Override
            public void close() {
                // no resources
            }

            @Override
            public int read(final char[] cbuf, final int off, final int len) throws IOException {
                if (++reads > 2) {
                    throw new IOException("boom");
                }
                cbuf[off] = 'a';
                return 1;
            }
        };
        assertThrows(IOException.class, () -> CSVDialectSniffer.sniff(broken, 16, COMMA, SEMICOLON));
    }

    @Test
    void testReadOnceInputWithoutMarkReset() throws IOException {
        final String input = "a;b\n1;2\n3;4\n5;6\n";
        // Three characters per read force many small reads; mark/reset is unsupported.
        final SniffResult result = CSVDialectSniffer.sniff(new ForwardOnlyReader(input, 3), 10, COMMA, SEMICOLON);
        assertFalse(result.isAmbiguous());
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testReplayReadsRemainderAfterPrefix() throws IOException {
        final String input = "a;b\n" + repeat("1;2\n", 100);
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), 8, COMMA, SEMICOLON);
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testReplayWithSingleCharReads() throws IOException {
        final String input = "a;b;c\n1;2;3\n4;5;6\n";
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), 12, COMMA, SEMICOLON);
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(input, readAllOneCharAtATime(result.getReader()));
    }

    @Test
    void testSemicolonSelectedWithQuotedCommas() throws IOException {
        final String input = "name;note\n\"foo\";\"a,b\"\n\"bar\";\"c,d\"\n";
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), 1024, COMMA, SEMICOLON);
        assertFalse(result.isAmbiguous());
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(1, result.getBestCandidates().size());
        final CandidateScore score = result.getBestCandidates().get(0);
        assertEquals(3, score.getCompleteRecordCount());
        assertEquals(2, score.getColumnCount());
        assertTrue(score.isConsistent());
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testSingleColumnSampleIsAmbiguous() throws IOException {
        final String input = "alpha\nbeta\ngamma\n";
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), 1024, COMMA, SEMICOLON);
        assertTrue(result.isAmbiguous());
        assertEquals(2, result.getBestCandidates().size());
        assertThrows(IllegalStateException.class, result::getFormat);
        assertEquals(input, readAll(result.getReader()));
    }

    @Test
    void testTieIsNotBrokenByCandidateOrder() throws IOException {
        final String input = "alpha\nbeta\n";
        final SniffResult first = CSVDialectSniffer.sniff(new StringReader(input), 1024, COMMA, SEMICOLON);
        final SniffResult second = CSVDialectSniffer.sniff(new StringReader(input), 1024, SEMICOLON, COMMA);
        assertTrue(first.isAmbiguous());
        assertTrue(second.isAmbiguous());
        assertEquals(2, first.getBestCandidates().size());
        assertEquals(2, second.getBestCandidates().size());
    }

    @Test
    void testTruncationCheckedExactlyAtLimit() throws IOException {
        // Input length equals the limit exactly: not truncated, all records count.
        final String input = "a;b\n1;2\n";
        final SniffResult exact = CSVDialectSniffer.sniff(new StringReader(input), input.length(), COMMA, SEMICOLON);
        assertFalse(exact.isAmbiguous());
        assertSame(SEMICOLON, exact.getFormat());
        assertEquals(2, exact.getBestCandidates().get(0).getCompleteRecordCount());
        // One character less: truncated, the last record is discarded from scoring.
        final SniffResult truncated = CSVDialectSniffer.sniff(new StringReader(input), input.length() - 1, COMMA, SEMICOLON);
        assertFalse(truncated.isAmbiguous());
        assertSame(SEMICOLON, truncated.getFormat());
        assertEquals(1, truncated.getBestCandidates().get(0).getCompleteRecordCount());
        assertEquals(input, readAll(truncated.getReader()));
    }

    @Test
    void testUnicodeAndSurrogatePairSplit() throws IOException {
        final String input = "姓名;城市\n\"张三\";\"北京,上海\"\n\"李四\";\"广州🎉深圳\"\n";
        // Split inside the surrogate pair of the emoji in the last record.
        final int limit = input.indexOf("🎉");
        final SniffResult result = CSVDialectSniffer.sniff(new StringReader(input), limit, COMMA, SEMICOLON);
        assertFalse(result.isAmbiguous());
        assertSame(SEMICOLON, result.getFormat());
        assertEquals(input, readAll(result.getReader()));
    }
}
