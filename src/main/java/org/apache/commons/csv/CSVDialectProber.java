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
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Probes a bounded character prefix of a {@link Reader} to detect the most likely CSV dialect
 * (delimiter, quote and escape rules) among caller-supplied candidate {@link CSVFormat}s.
 *
 * <p>
 * The prober reads at most {@code maxPrefixChars} characters from the input reader and buffers
 * them. It never relies on {@link Reader#markSupported() mark}/{@link Reader#reset() reset}; the
 * returned {@link Detection#getReader()} replays the buffered prefix in full and then continues
 * reading the underlying reader, so the concatenation of everything read from it is
 * character-for-character identical to the original input. The underlying reader is consumed
 * lazily and only once.
 * </p>
 *
 * <h2>Scoring rules</h2>
 *
 * <p>
 * Each candidate format is used to parse the buffered prefix and is scored as follows:
 * </p>
 * <ol>
 * <li><strong>Parse failure.</strong> If the candidate fails to parse a complete (non-truncated)
 * prefix, it is rejected and assigned score {@code -1}.</li>
 * <li><strong>Truncated tail.</strong> The prefix may end in the middle of a record (for example
 * inside a quoted field, possibly one spanning several lines). When the prefix was cut off at
 * {@code maxPrefixChars}, the sample is first trimmed back to the most recent record delimiter
 * (CR or LF); if it still does not parse, it is trimmed further, one record at a time, until it
 * parses or no complete record remains. An unfinished trailing record is therefore never treated
 * as a parse failure; a candidate with no complete records at all simply scores {@code 0}.</li>
 * <li><strong>No structural evidence.</strong> A candidate whose complete records consistently
 * contain a single column provides no evidence of structure (any delimiter fits a one-column
 * sample) and is assigned score {@code 0}. Candidates with no complete records at all also score
 * {@code 0}.</li>
 * <li><strong>Consistency.</strong> Otherwise the score is the number of complete records whose
 * column count equals the column count of the first complete record. Ragged input (varying column
 * counts) therefore scores lower than consistent input.</li>
 * </ol>
 *
 * <p>
 * The candidate with the highest score wins. If several candidates tie for the highest score
 * (including a tie at score {@code 0}), the detection is <em>ambiguous</em>: the result reports
 * {@link Detection#isAmbiguous()} and exposes every tied candidate through
 * {@link Detection#getBestCandidates()}. Ties are never broken by the order of the candidate list.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVDialectProber {

    /**
     * The outcome of a {@link CSVDialectProber} detection: the winning candidate (or the tied
     * candidates when ambiguous), the per-candidate scores, and a reader that replays the probed
     * prefix before continuing with the remaining input.
     */
    public static final class Detection {

        private final List<CSVFormat> candidates;
        private final int[] scores;
        private final List<CSVFormat> bestCandidates;
        private final Reader reader;
        private final boolean truncated;

        private Detection(final List<CSVFormat> candidates, final int[] scores, final Reader reader, final boolean truncated) {
            this.candidates = candidates;
            this.scores = scores;
            this.reader = reader;
            this.truncated = truncated;
            int best = Integer.MIN_VALUE;
            for (final int score : scores) {
                best = Math.max(best, score);
            }
            final List<CSVFormat> bestList = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] == best) {
                    bestList.add(candidates.get(i));
                }
            }
            this.bestCandidates = Collections.unmodifiableList(bestList);
        }

        /**
         * Gets all probed candidates in the order they were supplied.
         *
         * @return the probed candidates.
         */
        public List<CSVFormat> getCandidates() {
            return candidates;
        }

        /**
         * Gets the candidates tied for the highest score, in the order they were supplied.
         * Contains exactly one element when the detection is unambiguous.
         *
         * @return the best-scoring candidates.
         */
        public List<CSVFormat> getBestCandidates() {
            return bestCandidates;
        }

        /**
         * Gets the single winning candidate.
         *
         * @return the unique best-scoring candidate.
         * @throws IllegalStateException if the detection is ambiguous.
         */
        public CSVFormat getFormat() {
            if (isAmbiguous()) {
                throw new IllegalStateException("Ambiguous CSV dialect, tied candidates: " + bestCandidates);
            }
            return bestCandidates.get(0);
        }

        /**
         * Gets a reader that replays the buffered prefix in full and then continues reading the
         * underlying reader. Reading it to the end yields exactly the characters of the original
         * input, in order.
         *
         * @return the replaying reader.
         */
        public Reader getReader() {
            return reader;
        }

        /**
         * Gets the score assigned to the given candidate, or {@code -1} if the candidate was
         * rejected because it failed to parse the prefix.
         *
         * @param candidate a candidate previously passed to the prober.
         * @return the candidate score.
         * @throws IllegalArgumentException if the candidate was not probed.
         */
        public int getScore(final CSVFormat candidate) {
            final int index = candidates.indexOf(candidate);
            if (index < 0) {
                throw new IllegalArgumentException("Not a probed candidate: " + candidate);
            }
            return scores[index];
        }

        /**
         * Tests whether several candidates tied for the highest score. When ambiguous, no winner
         * is picked for the caller; inspect {@link #getBestCandidates()} to decide.
         *
         * @return true if more than one candidate achieved the best score.
         */
        public boolean isAmbiguous() {
            return bestCandidates.size() != 1;
        }

        /**
         * Tests whether the prefix was cut off at the configured maximum before the underlying
         * reader reached end-of-stream.
         *
         * @return true if the prefix was truncated at the limit.
         */
        public boolean isTruncated() {
            return truncated;
        }
    }

    /**
     * A reader that replays a buffered prefix and then delegates to the underlying reader.
     * Does not use mark/reset.
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
            Objects.requireNonNull(cbuf, "cbuf");
            if (off < 0 || len < 0 || len > cbuf.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            final int remaining = prefix.length() - pos;
            if (remaining <= 0) {
                return delegate.read(cbuf, off, len);
            }
            final int count = Math.min(remaining, len);
            prefix.getChars(pos, pos + count, cbuf, off);
            pos += count;
            return count;
        }

        @Override
        public boolean ready() throws IOException {
            return pos < prefix.length() || delegate.ready();
        }
    }

    /**
     * Detects the most likely CSV dialect of the input read from {@code reader}.
     *
     * @param reader the input reader; only its first {@code maxPrefixChars} characters are
     *        consumed during detection.
     * @param maxPrefixChars the maximum number of characters to buffer for detection; must be
     *        non-negative.
     * @param candidates the candidate dialects; must contain at least one. Each candidate carries
     *        its own delimiter, quote and escape rules.
     * @return the detection result, including a reader that replays the probed prefix.
     * @throws IOException if reading the prefix from {@code reader} fails.
     */
    public static Detection detect(final Reader reader, final int maxPrefixChars, final CSVFormat... candidates) throws IOException {
        return detect(reader, maxPrefixChars, Arrays.asList(candidates));
    }

    /**
     * Detects the most likely CSV dialect of the input read from {@code reader}.
     *
     * @param reader the input reader; only its first {@code maxPrefixChars} characters are
     *        consumed during detection.
     * @param maxPrefixChars the maximum number of characters to buffer for detection; must be
     *        non-negative.
     * @param candidates the candidate dialects; must contain at least one. Each candidate carries
     *        its own delimiter, quote and escape rules.
     * @return the detection result, including a reader that replays the probed prefix.
     * @throws IOException if reading the prefix from {@code reader} fails.
     */
    public static Detection detect(final Reader reader, final int maxPrefixChars, final List<CSVFormat> candidates) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(candidates, "candidates");
        if (maxPrefixChars < 0) {
            throw new IllegalArgumentException("maxPrefixChars must be non-negative: " + maxPrefixChars);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one candidate CSVFormat is required");
        }
        final StringBuilder prefix = new StringBuilder(Math.min(maxPrefixChars, 8192));
        final char[] chunk = new char[Math.min(Math.max(maxPrefixChars, 1), 4096)];
        int remaining = maxPrefixChars;
        boolean truncated = true;
        while (remaining > 0) {
            final int read = reader.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read < 0) {
                truncated = false;
                break;
            }
            prefix.append(chunk, 0, read);
            remaining -= read;
        }
        final String sample = prefix.toString();
        final List<CSVFormat> candidateList = Collections.unmodifiableList(new ArrayList<>(candidates));
        final int[] scores = new int[candidateList.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = score(candidateList.get(i), sample, truncated);
        }
        return new Detection(candidateList, scores, new PrefixReplayReader(sample, reader), truncated);
    }

    /**
     * Scores one candidate against the buffered prefix; see the class Javadoc for the rules.
     */
    private static int score(final CSVFormat candidate, final String sample, final boolean truncated) {
        String parseable = truncated ? trimBeforeLastRecord(sample) : sample;
        List<CSVRecord> records = null;
        while (parseable != null) {
            try (CSVParser parser = CSVParser.parse(new StringReader(parseable), candidate)) {
                records = parser.getRecords();
                break;
            } catch (final IOException | UncheckedIOException | IllegalStateException e) {
                if (!truncated) {
                    // The whole input was seen and it genuinely does not parse.
                    return -1;
                }
                // The cut may have landed inside a quoted field spanning several lines;
                // drop one more trailing record and retry.
                parseable = trimBeforeLastRecord(parseable);
            }
        }
        if (records == null || records.isEmpty()) {
            return 0;
        }
        final int columns = records.get(0).size();
        if (columns <= 1) {
            // A single-column sample gives no structural evidence for any delimiter.
            return 0;
        }
        int valid = 0;
        for (final CSVRecord record : records) {
            if (record.size() == columns) {
                valid++;
            }
        }
        return valid;
    }

    /**
     * Trims the sample to the end of its last complete record. Returns the sample unchanged when
     * it is empty or already ends with a record delimiter; returns {@code null} when it contains
     * no record delimiter at all.
     */
    private static String trimBeforeLastRecord(final String sample) {
        final int length = sample.length();
        if (length == 0 || isRecordDelimiter(sample.charAt(length - 1))) {
            return sample;
        }
        for (int i = length - 1; i >= 0; i--) {
            if (isRecordDelimiter(sample.charAt(i))) {
                return sample.substring(0, i + 1);
            }
        }
        return null;
    }

    private static boolean isRecordDelimiter(final char c) {
        return c == Constants.CR || c == Constants.LF;
    }

    private CSVDialectProber() {
        // utility class
    }
}
