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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Sniffs the dialect of CSV input by scoring caller-supplied candidate {@link CSVFormat}s against a bounded prefix of the
 * input.
 *
 * <p>
 * The sniffer reads at most {@code maxPrefixLength} characters from the given {@link Reader}, then parses that prefix once
 * per candidate format. Because the prefix may end in the middle of a record (possibly inside a quoted field), an
 * incomplete trailing record is never treated as a parse failure. After sniffing, {@link SniffResult#getReader()} returns a
 * reader that replays the consumed prefix transparently and then continues with the remaining input, so no input is lost
 * and the underlying reader's {@code mark}/{@code reset} support is never used.
 * </p>
 *
 * <h2>Scoring rules</h2>
 *
 * <p>
 * Each candidate is scored independently as follows:
 * </p>
 * <ol>
 * <li>The prefix is parsed with the candidate format. If the prefix was truncated at {@code maxPrefixLength} characters,
 * the last parsed record is discarded because it may be incomplete; a parse error on a truncated prefix is tolerated for
 * the same reason (for example, the prefix may end inside a quoted field). A parse error on an untruncated prefix
 * disqualifies the candidate.</li>
 * <li>A candidate is <em>consistent</em> if all of its complete records have the same column count. Inconsistent
 * candidates, and candidates with no complete records, are invalid and cannot win.</li>
 * <li>Valid candidates are ranked by column count (more columns first), then by complete record count (more records
 * first). The idea is that the real dialect explains more structure (columns) in the same input.</li>
 * <li>If exactly one candidate reaches the best rank, it is the detected dialect. If several candidates tie at the best
 * rank, the result is {@linkplain SniffResult#isAmbiguous() ambiguous} and all tied candidates are reported through
 * {@link SniffResult#getBestCandidates()}; the sniffer never breaks a tie by candidate list order. If every candidate is
 * invalid, the result is also ambiguous.</li>
 * </ol>
 *
 * <p>
 * Example: a semicolon-separated file containing quoted commas is detected with
 * </p>
 *
 * <pre>
 * CSVFormat comma = CSVFormat.DEFAULT.builder().setDelimiter(',').get();
 * CSVFormat semicolon = CSVFormat.DEFAULT.builder().setDelimiter(';').get();
 * CSVDialectSniffer.SniffResult result = CSVDialectSniffer.sniff(reader, 4096, comma, semicolon);
 * if (!result.isAmbiguous()) {
 *     try (CSVParser parser = CSVParser.parse(result.getReader(), result.getFormat())) {
 *         // ...
 *     }
 * }
 * </pre>
 *
 * <p>
 * The demonstration command {@code mvn -q -Dtest=CSVDialectSnifferTest test} runs the sniffing tests (determined result,
 * ambiguous result, and full prefix replay) in well under 90 seconds.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVDialectSniffer {

    /**
     * The score of one candidate {@link CSVFormat} against the sniffed prefix.
     */
    public static final class CandidateScore {

        private final CSVFormat format;
        private final int completeRecordCount;
        private final int columnCount;
        private final boolean consistent;

        private CandidateScore(final CSVFormat format, final int completeRecordCount, final int columnCount, final boolean consistent) {
            this.format = format;
            this.completeRecordCount = completeRecordCount;
            this.columnCount = columnCount;
            this.consistent = consistent;
        }

        /**
         * Gets the column count shared by all complete records, or -1 if the candidate is not consistent.
         *
         * @return the consistent column count, or -1.
         */
        public int getColumnCount() {
            return columnCount;
        }

        /**
         * Gets the number of complete records parsed from the prefix.
         *
         * @return the complete record count.
         */
        public int getCompleteRecordCount() {
            return completeRecordCount;
        }

        /**
         * Gets the candidate format.
         *
         * @return the candidate format.
         */
        public CSVFormat getFormat() {
            return format;
        }

        /**
         * Tests whether all complete records have the same column count.
         *
         * @return true if the candidate parsed consistently.
         */
        public boolean isConsistent() {
            return consistent;
        }

        /**
         * Tests whether this candidate can win: consistent and at least one complete record.
         *
         * @return true if this candidate is a valid detection result.
         */
        public boolean isValid() {
            return consistent && completeRecordCount > 0;
        }

        @Override
        public String toString() {
            return "CandidateScore[valid=" + isValid() + ", columns=" + columnCount + ", completeRecords=" + completeRecordCount + ", format=" +
                format + "]";
        }
    }

    /**
     * The outcome of a sniffing run: the detected dialect (or the tied best candidates when ambiguous) and a reader that
     * replays the consumed prefix before the remaining input.
     */
    public static final class SniffResult {

        private final List<CandidateScore> bestCandidates;
        private final List<CandidateScore> candidateScores;
        private final Reader reader;

        private SniffResult(final List<CandidateScore> bestCandidates, final List<CandidateScore> candidateScores, final Reader reader) {
            this.bestCandidates = bestCandidates;
            this.candidateScores = candidateScores;
            this.reader = reader;
        }

        /**
         * Gets the candidates tied at the best rank. Contains exactly one element when the result is unambiguous.
         *
         * @return the best candidates, never modified by candidate list order.
         */
        public List<CandidateScore> getBestCandidates() {
            return bestCandidates;
        }

        /**
         * Gets the scores of all candidates, in the order the candidates were supplied.
         *
         * @return all candidate scores.
         */
        public List<CandidateScore> getCandidateScores() {
            return candidateScores;
        }

        /**
         * Gets the detected dialect.
         *
         * @return the single best candidate format.
         * @throws IllegalStateException if the result is {@linkplain #isAmbiguous() ambiguous}.
         */
        public CSVFormat getFormat() {
            if (isAmbiguous()) {
                throw new IllegalStateException("Ambiguous CSV dialect, best candidates: " + bestCandidates);
            }
            return bestCandidates.get(0).getFormat();
        }

        /**
         * Gets a reader that replays the prefix consumed during sniffing and then continues with the remaining input. The
         * returned reader does not rely on {@code mark}/{@code reset} of the underlying reader.
         *
         * @return a reader over the complete input.
         */
        public Reader getReader() {
            return reader;
        }

        /**
         * Tests whether no single dialect could be determined, either because several candidates tie at the best rank or
         * because no candidate parsed any complete record.
         *
         * @return true if the result is ambiguous.
         */
        public boolean isAmbiguous() {
            return bestCandidates.size() != 1 || !bestCandidates.get(0).isValid();
        }
    }

    /**
     * A reader that replays a buffered prefix and then delegates to the remaining input, without using
     * {@code mark}/{@code reset}.
     */
    private static final class PrefixReplayReader extends Reader {

        private final String prefix;
        private final Reader delegate;
        private int pos;

        PrefixReplayReader(final String prefix, final Reader delegate) {
            this.prefix = prefix;
            this.delegate = delegate;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public int read() throws IOException {
            if (pos < prefix.length()) {
                return prefix.charAt(pos++);
            }
            return delegate.read();
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) throws IOException {
            if (pos < prefix.length()) {
                final int n = Math.min(len, prefix.length() - pos);
                prefix.getChars(pos, pos + n, cbuf, off);
                pos += n;
                return n;
            }
            return delegate.read(cbuf, off, len);
        }

        @Override
        public boolean ready() throws IOException {
            return pos < prefix.length() || delegate.ready();
        }
    }

    /**
     * Compares two scores: valid beats invalid, then more columns, then more complete records.
     */
    private static int compare(final CandidateScore left, final CandidateScore right) {
        if (left.isValid() != right.isValid()) {
            return left.isValid() ? 1 : -1;
        }
        if (!left.isValid()) {
            return 0;
        }
        if (left.columnCount != right.columnCount) {
            return Integer.compare(left.columnCount, right.columnCount);
        }
        return Integer.compare(left.completeRecordCount, right.completeRecordCount);
    }

    /**
     * Scores one candidate against the prefix. When the prefix was truncated, the last record is discarded and parse
     * errors are tolerated; otherwise a parse error disqualifies the candidate.
     */
    private static CandidateScore score(final CSVFormat format, final String prefix, final boolean truncated) {
        final List<CSVRecord> records = new ArrayList<>();
        boolean parseError = false;
        try (CSVParser parser = CSVParser.parse(prefix, format)) {
            for (final CSVRecord record : parser) {
                records.add(record);
            }
        } catch (final IOException | RuntimeException e) {
            parseError = true;
        }
        if (parseError && !truncated) {
            return new CandidateScore(format, 0, -1, false);
        }
        int complete = records.size();
        if (truncated && !parseError && complete > 0) {
            // The last record may have been cut off by the prefix limit. (On a parse error the incomplete record was
            // never emitted, so there is nothing to discard.)
            complete--;
        }
        if (complete == 0) {
            return new CandidateScore(format, 0, -1, false);
        }
        final int columns = records.get(0).size();
        for (int i = 1; i < complete; i++) {
            if (records.get(i).size() != columns) {
                return new CandidateScore(format, complete, -1, false);
            }
        }
        return new CandidateScore(format, complete, columns, true);
    }

    /**
     * Sniffs the CSV dialect of the given reader.
     *
     * @param reader the input reader, consumed for at most {@code maxPrefixLength + 1} characters.
     * @param maxPrefixLength the maximum number of prefix characters to read for scoring, must be positive.
     * @param candidates the candidate dialects, must not be empty or contain nulls.
     * @return the sniffing result, including a reader that replays the consumed prefix.
     * @throws IOException if reading the prefix fails.
     * @throws IllegalArgumentException if {@code maxPrefixLength} is not positive or {@code candidates} is empty.
     * @throws NullPointerException if {@code reader}, {@code candidates}, or a candidate is null.
     */
    public static SniffResult sniff(final Reader reader, final int maxPrefixLength, final CSVFormat... candidates) throws IOException {
        return sniff(reader, maxPrefixLength, Arrays.asList(candidates));
    }

    /**
     * Sniffs the CSV dialect of the given reader.
     *
     * @param reader the input reader, consumed for at most {@code maxPrefixLength + 1} characters.
     * @param maxPrefixLength the maximum number of prefix characters to read for scoring, must be positive.
     * @param candidates the candidate dialects, must not be empty or contain nulls.
     * @return the sniffing result, including a reader that replays the consumed prefix.
     * @throws IOException if reading the prefix fails.
     * @throws IllegalArgumentException if {@code maxPrefixLength} is not positive or {@code candidates} is empty.
     * @throws NullPointerException if {@code reader}, {@code candidates}, or a candidate is null.
     */
    public static SniffResult sniff(final Reader reader, final int maxPrefixLength, final List<CSVFormat> candidates) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(candidates, "candidates");
        if (maxPrefixLength < 1) {
            throw new IllegalArgumentException("maxPrefixLength must be positive: " + maxPrefixLength);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        final char[] buffer = new char[maxPrefixLength];
        int count = 0;
        while (count < maxPrefixLength) {
            final int n = reader.read(buffer, count, maxPrefixLength - count);
            if (n < 0) {
                break;
            }
            count += n;
        }
        // Verify at the truncation position whether more input exists; the probe character is replayed but not parsed.
        boolean truncated = false;
        final String replayPrefix;
        if (count == maxPrefixLength) {
            final int extra = reader.read();
            if (extra < 0) {
                replayPrefix = new String(buffer);
            } else {
                truncated = true;
                replayPrefix = new String(buffer) + (char) extra;
            }
        } else {
            replayPrefix = new String(buffer, 0, count);
        }
        final String parsePrefix = new String(buffer, 0, count);
        final List<CandidateScore> scores = new ArrayList<>(candidates.size());
        for (final CSVFormat candidate : candidates) {
            scores.add(score(Objects.requireNonNull(candidate, "candidate"), parsePrefix, truncated));
        }
        CandidateScore best = null;
        for (final CandidateScore score : scores) {
            if (best == null || compare(score, best) > 0) {
                best = score;
            }
        }
        final List<CandidateScore> bestCandidates = new ArrayList<>();
        for (final CandidateScore score : scores) {
            if (compare(score, best) == 0) {
                bestCandidates.add(score);
            }
        }
        return new SniffResult(Collections.unmodifiableList(bestCandidates), Collections.unmodifiableList(scores),
            new PrefixReplayReader(replayPrefix, reader));
    }

    private CSVDialectSniffer() {
        // no instances
    }
}
