/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.csv.sequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Validates a read-only CSV record stream against a caller-declared finite state machine, keyed by a business key
 * column.
 *
 * <p>
 * Contract:
 * </p>
 * <ul>
 * <li>Each record carries a business key and a target state, both named by header column.</li>
 * <li>A record updates its key's state only if <em>both</em> gates pass: (1) every registered
 * {@link FieldConverter} converts its field, and (2) the target state is an allowed transition from the key's
 * current state (or an initial state for a key seen for the first time).</li>
 * <li>A rejected record is reported as a {@link Rejection} carrying the record number, the key, the old state, and
 * the expected state set; it never advances the key's state, so it cannot make later illegal records legal.</li>
 * <li>The number of simultaneously active keys is bounded by {@code maxActiveKeys}. A record for a new key that
 * would exceed the bound is rejected with {@link RejectionReason#CAPACITY_EXCEEDED}; still-active states are never
 * evicted. A key stops being active (freeing its slot) only when it reaches a declared final state.</li>
 * <li>The validator only reads the CSV input; it never writes business data. Converted field values are
 * discarded.</li>
 * </ul>
 *
 * <p>
 * Gate evaluation order per record: field conversion, then capacity (new keys only), then transition legality.
 * </p>
 *
 * @since 1.15.0
 */
public final class CsvSequenceValidator {

    /**
     * Builds {@link CsvSequenceValidator} instances.
     */
    public static final class Builder {

        private final Map<String, Set<String>> transitions = new LinkedHashMap<>();
        private final Set<String> initialStates = new LinkedHashSet<>();
        private final Set<String> finalStates = new LinkedHashSet<>();
        private final Map<String, FieldConverter> fieldConverters = new LinkedHashMap<>();
        private String keyColumn;
        private String stateColumn;
        private int maxActiveKeys = -1;

        private Builder() {
        }

        /**
         * Declares an allowed transition between two states of the same key.
         *
         * @param fromState the source state.
         * @param toState the target state.
         * @return this builder.
         */
        public Builder transition(final String fromState, final String toState) {
            transitions.computeIfAbsent(Objects.requireNonNull(fromState, "fromState"), k -> new LinkedHashSet<>())
                .add(Objects.requireNonNull(toState, "toState"));
            return this;
        }

        /**
         * Declares states a previously unseen key may start in.
         *
         * @param states the initial states.
         * @return this builder.
         */
        public Builder initialStates(final String... states) {
            for (final String state : states) {
                initialStates.add(Objects.requireNonNull(state, "state"));
            }
            return this;
        }

        /**
         * Declares states that complete a key's life cycle; reaching one frees the key's capacity slot.
         *
         * @param states the final states.
         * @return this builder.
         */
        public Builder finalStates(final String... states) {
            for (final String state : states) {
                finalStates.add(Objects.requireNonNull(state, "state"));
            }
            return this;
        }

        /**
         * Sets the maximum number of simultaneously active keys. Excess new keys are rejected, never evicted.
         *
         * @param maxActiveKeys a positive bound.
         * @return this builder.
         */
        public Builder maxActiveKeys(final int maxActiveKeys) {
            if (maxActiveKeys <= 0) {
                throw new IllegalArgumentException("maxActiveKeys must be positive: " + maxActiveKeys);
            }
            this.maxActiveKeys = maxActiveKeys;
            return this;
        }

        /**
         * Sets the header name of the business key column.
         *
         * @param keyColumn the column name.
         * @return this builder.
         */
        public Builder keyColumn(final String keyColumn) {
            this.keyColumn = Objects.requireNonNull(keyColumn, "keyColumn");
            return this;
        }

        /**
         * Sets the header name of the target state column.
         *
         * @param stateColumn the column name.
         * @return this builder.
         */
        public Builder stateColumn(final String stateColumn) {
            this.stateColumn = Objects.requireNonNull(stateColumn, "stateColumn");
            return this;
        }

        /**
         * Registers a converting validator for a field; a failing conversion rejects the whole record.
         *
         * @param column the header name of the field.
         * @param converter the converter.
         * @return this builder.
         */
        public Builder fieldConverter(final String column, final FieldConverter converter) {
            fieldConverters.put(Objects.requireNonNull(column, "column"),
                Objects.requireNonNull(converter, "converter"));
            return this;
        }

        /**
         * Builds the validator.
         *
         * @return a new validator.
         * @throws IllegalStateException if required settings are missing.
         */
        public CsvSequenceValidator build() {
            if (keyColumn == null || stateColumn == null) {
                throw new IllegalStateException("keyColumn and stateColumn are required");
            }
            if (maxActiveKeys <= 0) {
                throw new IllegalStateException("maxActiveKeys is required and must be positive");
            }
            if (initialStates.isEmpty()) {
                throw new IllegalStateException("at least one initial state is required");
            }
            return new CsvSequenceValidator(this);
        }
    }

    /**
     * Creates a new builder.
     *
     * @return a builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String keyColumn;
    private final String stateColumn;
    private final Map<String, Set<String>> transitions;
    private final Set<String> initialStates;
    private final Set<String> finalStates;
    private final int maxActiveKeys;
    private final Map<String, FieldConverter> fieldConverters;

    private CsvSequenceValidator(final Builder builder) {
        this.keyColumn = builder.keyColumn;
        this.stateColumn = builder.stateColumn;
        this.transitions = deepCopy(builder.transitions);
        this.initialStates = new LinkedHashSet<>(builder.initialStates);
        this.finalStates = new LinkedHashSet<>(builder.finalStates);
        this.maxActiveKeys = builder.maxActiveKeys;
        this.fieldConverters = new LinkedHashMap<>(builder.fieldConverters);
    }

    private static Map<String, Set<String>> deepCopy(final Map<String, Set<String>> source) {
        final Map<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((from, tos) -> copy.put(from, Collections.unmodifiableSet(new LinkedHashSet<>(tos))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Validates every record of the given parser, consuming it fully.
     *
     * @param parser the CSV source, opened with a header matching the configured column names; only read, never
     *        modified.
     * @return the outcome: still-active states and all rejections in input order.
     */
    public ValidationResult validate(final CSVParser parser) {
        final Map<String, String> activeStates = new LinkedHashMap<>();
        final List<Rejection> rejections = new ArrayList<>();
        for (final CSVRecord record : parser) {
            final String key = record.get(keyColumn);
            final String targetState = record.get(stateColumn);
            final String oldState = activeStates.get(key); // null when the key is not active

            // Gate 1: field conversion.
            final String conversionFailure = convertFields(record);
            if (conversionFailure != null) {
                rejections.add(new Rejection(record.getRecordNumber(), key, oldState, targetState,
                        expectedStates(oldState), RejectionReason.FIELD_CONVERSION, conversionFailure));
                continue;
            }

            // Gate 2: capacity, only for keys that would newly become active.
            if (oldState == null && activeStates.size() >= maxActiveKeys) {
                rejections.add(new Rejection(record.getRecordNumber(), key, null, targetState,
                        Collections.emptySet(), RejectionReason.CAPACITY_EXCEEDED,
                        "active key capacity " + maxActiveKeys + " exhausted"));
                continue;
            }

            // Gate 3: transition legality.
            final Set<String> expected = expectedStates(oldState);
            if (!expected.contains(targetState)) {
                rejections.add(new Rejection(record.getRecordNumber(), key, oldState, targetState, expected,
                        RejectionReason.ILLEGAL_TRANSITION, null));
                continue;
            }

            // Both gates passed: advance (or complete) the key's state.
            if (finalStates.contains(targetState)) {
                activeStates.remove(key);
            } else {
                activeStates.put(key, targetState);
            }
        }
        return new ValidationResult(activeStates, rejections);
    }

    private String convertFields(final CSVRecord record) {
        for (final Map.Entry<String, FieldConverter> entry : fieldConverters.entrySet()) {
            try {
                entry.getValue().convert(record.get(entry.getKey()));
            } catch (final RuntimeException e) {
                return "column '" + entry.getKey() + "': " + e.getMessage();
            }
        }
        return null;
    }

    private Set<String> expectedStates(final String oldState) {
        if (oldState == null) {
            return initialStates;
        }
        final Set<String> allowed = transitions.get(oldState);
        return allowed != null ? allowed : Collections.emptySet();
    }
}
