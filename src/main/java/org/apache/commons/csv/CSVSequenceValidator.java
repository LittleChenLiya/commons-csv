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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a sequence of {@link CSVRecord}s against a per-business-key finite state machine.
 *
 * <p>
 * Each record is associated with a business key (for example an order id) and declares a target state. The
 * validator keeps one current state per key. A record updates the current state of its key only when both of
 * the following hold:
 * </p>
 * <ol>
 * <li><b>Field conversion</b>: the key and the target state are successfully parsed from the record by the
 * configured {@link FieldParser}s.</li>
 * <li><b>Transition legality</b>: the target state is among the allowed successors of the current state, as
 * declared by the caller in the transition map.</li>
 * </ol>
 *
 * <h2>Contract</h2>
 * <ul>
 * <li>The caller declares the {@linkplain Builder#initialState(Object) initial state}, the
 * {@linkplain Builder#transitions(Map) allowed state transitions}, the {@linkplain Builder#maxKeys(int)
 * maximum number of distinct keys}, and the {@linkplain Builder#keyParser(FieldParser) key} and
 * {@linkplain Builder#stateParser(FieldParser) state} field parsers.</li>
 * <li>For a record whose key has not been seen before, the old state is the initial state. If the target
 * state equals the initial state, the key is established. Otherwise the transition from the initial state to
 * the target must be allowed; if it is not, the record is illegal and the state is unchanged.</li>
 * <li>For a record whose key has been seen before, the transition from the current state to the target must
 * be allowed; if it is not, the record is illegal and the state is unchanged.</li>
 * <li>If a new key would exceed the maximum number of distinct keys, the record is illegal
 * ({@link FailureReason#CAPACITY}); no still-active state is evicted or changed.</li>
 * <li>An illegal row carries the record number, the old state, and the expected set (the allowed successors
 * of the old state). Illegal rows never advance the state, so subsequent records are validated against the
 * unchanged state.</li>
 * <li>The validator is read-only: it reads records but never writes business data.</li>
 * </ul>
 *
 * <p>
 * Instances are stateless apart from their configuration and may be reused and shared across threads.
 * </p>
 *
 * @param <K> the business key type
 * @param <S> the state type
 * @since 1.15.0
 */
public final class CSVSequenceValidator<K, S> {

    /**
     * The reason a row was rejected.
     */
    public enum FailureReason {

        /** A field could not be converted to the key or state type. */
        FIELD_CONVERSION,

        /** The target state is not an allowed successor of the old state. */
        ILLEGAL_TRANSITION,

        /** A new key would exceed the maximum number of distinct keys. */
        CAPACITY
    }

    /**
     * A row that failed validation. Instances carry the record number, the old state, and the expected set
     * (the allowed successors of the old state).
     *
     * @param <S> the state type
     */
    public static final class IllegalRow<S> {

        private final long recordNumber;
        private final S oldState;
        private final Set<S> expectedStates;
        private final FailureReason reason;
        private final String message;

        IllegalRow(final long recordNumber, final S oldState, final Set<S> expectedStates,
                final FailureReason reason, final String message) {
            this.recordNumber = recordNumber;
            this.oldState = oldState;
            this.expectedStates = Collections.unmodifiableSet(new LinkedHashSet<>(expectedStates));
            this.reason = reason;
            this.message = message;
        }

        /**
         * Returns the 1-based record number of the illegal row.
         *
         * @return the record number
         */
        public long getRecordNumber() {
            return recordNumber;
        }

        /**
         * Returns the state the key was in before the row, or {@code null} if the key could not be parsed.
         *
         * @return the old state, possibly null
         */
        public S getOldState() {
            return oldState;
        }

        /**
         * Returns the expected set: the allowed successors of the old state. Empty if the old state is
         * unknown.
         *
         * @return the expected set, never null
         */
        public Set<S> getExpectedStates() {
            return expectedStates;
        }

        /**
         * Returns the failure reason.
         *
         * @return the failure reason
         */
        public FailureReason getReason() {
            return reason;
        }

        /**
         * Returns a human-readable failure message.
         *
         * @return the message
         */
        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "IllegalRow{recordNumber=" + recordNumber + ", oldState=" + oldState + ", expectedStates="
                    + expectedStates + ", reason=" + reason + ", message='" + message + "'}";
        }
    }

    /**
     * The result of validating a sequence of records: the illegal rows and the final per-key states.
     *
     * @param <K> the business key type
     * @param <S> the state type
     */
    public static final class Result<K, S> {

        private final List<IllegalRow<S>> illegalRows;
        private final Map<K, S> states;

        Result(final List<IllegalRow<S>> illegalRows, final Map<K, S> states) {
            this.illegalRows = Collections.unmodifiableList(new ArrayList<>(illegalRows));
            this.states = Collections.unmodifiableMap(new HashMap<>(states));
        }

        /**
         * Returns the illegal rows in encounter order.
         *
         * @return the illegal rows, never null
         */
        public List<IllegalRow<S>> getIllegalRows() {
            return illegalRows;
        }

        /**
         * Returns the final per-key states. Keys whose rows were all rejected are absent.
         *
         * @return the final states, never null
         */
        public Map<K, S> getStates() {
            return states;
        }

        /**
         * Returns whether every row was legal.
         *
         * @return true if no illegal row was found
         */
        public boolean isSuccess() {
            return illegalRows.isEmpty();
        }

        @Override
        public String toString() {
            return "Result{illegalRows=" + illegalRows + ", states=" + states + '}';
        }
    }

    /**
     * Converts a {@link CSVRecord} into a typed value. Implementations throw {@link Exception} to signal a
     * field conversion failure.
     *
     * @param <T> the value type
     */
    @FunctionalInterface
    public interface FieldParser<T> {

        /**
         * Parses a value from the given record.
         *
         * @param record the record to parse
         * @return the parsed value
         * @throws Exception if the field cannot be converted
         */
        T parse(CSVRecord record) throws Exception;
    }

    /**
     * Returns a new builder.
     *
     * @param <K> the business key type
     * @param <S> the state type
     * @return a new builder
     */
    public static <K, S> Builder<K, S> builder() {
        return new Builder<>();
    }

    /**
     * Builds {@link CSVSequenceValidator} instances.
     *
     * @param <K> the business key type
     * @param <S> the state type
     */
    public static final class Builder<K, S> {

        private S initialState;
        private final Map<S, Set<S>> transitions = new HashMap<>();
        private int maxKeys = Integer.MAX_VALUE;
        private FieldParser<K> keyParser;
        private FieldParser<S> stateParser;

        Builder() {
        }

        /**
         * Sets the initial state assigned to a key before its first legal record.
         *
         * @param initialState the initial state, never null
         * @return this builder
         */
        public Builder<K, S> initialState(final S initialState) {
            this.initialState = Objects.requireNonNull(initialState, "initialState");
            return this;
        }

        /**
         * Sets the allowed state transitions. The map is copied; later changes to it do not affect the
         * validator.
         *
         * @param transitions the allowed transitions, never null
         * @return this builder
         */
        public Builder<K, S> transitions(final Map<S, ? extends Set<S>> transitions) {
            Objects.requireNonNull(transitions, "transitions");
            this.transitions.clear();
            for (final Map.Entry<S, ? extends Set<S>> entry : transitions.entrySet()) {
                this.transitions.put(entry.getKey(),
                        Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
            }
            return this;
        }

        /**
         * Adds one allowed transition.
         *
         * @param from the old state
         * @param to   the allowed successors
         * @return this builder
         */
        public Builder<K, S> transition(final S from, final Set<S> to) {
            Objects.requireNonNull(from, "from");
            this.transitions.put(from, Collections.unmodifiableSet(new LinkedHashSet<>(to)));
            return this;
        }

        /**
         * Sets the maximum number of distinct keys. A new key that would exceed this limit is rejected with
         * {@link FailureReason#CAPACITY}.
         *
         * @param maxKeys the maximum number of distinct keys, must be positive
         * @return this builder
         */
        public Builder<K, S> maxKeys(final int maxKeys) {
            if (maxKeys <= 0) {
                throw new IllegalArgumentException("maxKeys must be positive");
            }
            this.maxKeys = maxKeys;
            return this;
        }

        /**
         * Sets the key field parser.
         *
         * @param keyParser the key parser, never null
         * @return this builder
         */
        public Builder<K, S> keyParser(final FieldParser<K> keyParser) {
            this.keyParser = Objects.requireNonNull(keyParser, "keyParser");
            return this;
        }

        /**
         * Sets the target state field parser.
         *
         * @param stateParser the state parser, never null
         * @return this builder
         */
        public Builder<K, S> stateParser(final FieldParser<S> stateParser) {
            this.stateParser = Objects.requireNonNull(stateParser, "stateParser");
            return this;
        }

        /**
         * Builds the validator.
         *
         * @return the validator
         */
        public CSVSequenceValidator<K, S> build() {
            Objects.requireNonNull(initialState, "initialState must be set");
            Objects.requireNonNull(keyParser, "keyParser must be set");
            Objects.requireNonNull(stateParser, "stateParser must be set");
            return new CSVSequenceValidator<>(initialState, transitions, maxKeys, keyParser, stateParser);
        }
    }

    private final S initialState;
    private final Map<S, Set<S>> transitions;
    private final int maxKeys;
    private final FieldParser<K> keyParser;
    private final FieldParser<S> stateParser;

    private CSVSequenceValidator(final S initialState, final Map<S, Set<S>> transitions, final int maxKeys,
            final FieldParser<K> keyParser, final FieldParser<S> stateParser) {
        this.initialState = initialState;
        this.transitions = Collections.unmodifiableMap(new HashMap<>(transitions));
        this.maxKeys = maxKeys;
        this.keyParser = keyParser;
        this.stateParser = stateParser;
    }

    /**
     * Validates the given records. The validator is read-only: it never writes business data.
     *
     * @param records the records to validate, never null
     * @return the result, never null
     */
    public Result<K, S> validate(final Iterable<CSVRecord> records) {
        final Map<K, S> states = new HashMap<>();
        final List<IllegalRow<S>> illegalRows = new ArrayList<>();
        for (final CSVRecord record : records) {
            final long recordNumber = record.getRecordNumber();
            final K key;
            try {
                key = keyParser.parse(record);
            } catch (final Exception e) {
                illegalRows.add(new IllegalRow<>(recordNumber, null, Collections.emptySet(),
                        FailureReason.FIELD_CONVERSION, "key field conversion failed: " + e.getMessage()));
                continue;
            }
            final S target;
            try {
                target = stateParser.parse(record);
            } catch (final Exception e) {
                final S oldState = states.get(key);
                illegalRows.add(new IllegalRow<>(recordNumber, oldState, expectedFor(oldState),
                        FailureReason.FIELD_CONVERSION, "state field conversion failed: " + e.getMessage()));
                continue;
            }
            final S oldState = states.get(key);
            if (oldState == null) {
                handleNewKey(recordNumber, key, target, states, illegalRows);
            } else {
                final Set<S> expected = expectedFor(oldState);
                if (expected.contains(target)) {
                    states.put(key, target);
                } else {
                    illegalRows.add(new IllegalRow<>(recordNumber, oldState, expected,
                            FailureReason.ILLEGAL_TRANSITION,
                            "illegal transition from " + oldState + " to " + target));
                }
            }
        }
        return new Result<>(illegalRows, states);
    }

    private void handleNewKey(final long recordNumber, final K key, final S target, final Map<K, S> states,
            final List<IllegalRow<S>> illegalRows) {
        if (states.size() >= maxKeys) {
            illegalRows.add(new IllegalRow<>(recordNumber, initialState, expectedFor(initialState),
                    FailureReason.CAPACITY, "key count exceeds maximum of " + maxKeys));
            return;
        }
        if (target.equals(initialState)) {
            states.put(key, target);
            return;
        }
        final Set<S> expected = expectedFor(initialState);
        if (expected.contains(target)) {
            states.put(key, target);
        } else {
            illegalRows.add(new IllegalRow<>(recordNumber, initialState, expected,
                    FailureReason.ILLEGAL_TRANSITION,
                    "illegal transition from " + initialState + " to " + target));
        }
    }

    private Set<S> expectedFor(final S state) {
        if (state == null) {
            return Collections.emptySet();
        }
        return transitions.getOrDefault(state, Collections.emptySet());
    }
}
