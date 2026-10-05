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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.apache.commons.csv.CSVSequenceValidator.FailureReason;
import org.apache.commons.csv.CSVSequenceValidator.IllegalRow;
import org.apache.commons.csv.CSVSequenceValidator.Result;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVSequenceValidator} with small interleaved fixtures.
 */
class CSVSequenceValidatorTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/org/apache/commons/csv/sequence");

    private static final String KEY_COLUMN = "order";
    private static final String STATE_COLUMN = "state";

    private static Map<OrderState, java.util.Set<OrderState>> transitions() {
        final Map<OrderState, java.util.Set<OrderState>> map = new EnumMap<>(OrderState.class);
        map.put(OrderState.NEW, EnumSet.of(OrderState.PAID));
        map.put(OrderState.PAID, EnumSet.of(OrderState.SENT));
        map.put(OrderState.SENT, Collections.emptySet());
        return map;
    }

    private static CSVSequenceValidator<String, OrderState> validator(final int maxKeys) {
        return CSVSequenceValidator.<String, OrderState>builder()
                .initialState(OrderState.NEW)
                .transitions(transitions())
                .maxKeys(maxKeys)
                .keyParser(record -> {
                    final String key = record.get(KEY_COLUMN).trim();
                    if (key.isEmpty()) {
                        throw new IllegalArgumentException("empty key");
                    }
                    return key;
                })
                .stateParser(record -> OrderState.fromCode(record.get(STATE_COLUMN).trim()))
                .build();
    }

    private static Result<String, OrderState> validate(final String fixture, final int maxKeys) throws IOException {
        final Path path = FIXTURES.resolve(fixture);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                        .parse(reader)) {
            return validator(maxKeys).validate(parser);
        }
    }

    private static Result<String, OrderState> validate(final String fixture) throws IOException {
        return validate(fixture, Integer.MAX_VALUE);
    }

    @Test
    void interleavedOrdersAllPass() throws IOException {
        final Result<String, OrderState> result = validate("orders-interleaved.csv");
        assertTrue(result.isSuccess(), () -> "unexpected illegal rows: " + result.getIllegalRows());
        assertEquals(OrderState.SENT, result.getStates().get("a"));
        assertEquals(OrderState.SENT, result.getStates().get("b"));
    }

    @Test
    void differentOrdersTrackStateIndependently() throws IOException {
        final Result<String, OrderState> result = validate("orders-interleaved.csv");
        // a and b advance in lockstep but each has its own state; both end at SENT
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("a", "b")), result.getStates().keySet());
        assertEquals(OrderState.SENT, result.getStates().get("a"));
        assertEquals(OrderState.SENT, result.getStates().get("b"));
    }

    @Test
    void illegalTransitionRejectedAndStateUnchanged() throws IOException {
        // a: new (ok), sent (illegal, stays new), paid (ok from new), sent (ok from paid)
        final Result<String, OrderState> result = validate("orders-illegal-transition.csv");
        assertFalse(result.isSuccess());
        assertEquals(1, result.getIllegalRows().size());
        final IllegalRow<OrderState> illegal = result.getIllegalRows().get(0);
        assertEquals(2, illegal.getRecordNumber());
        assertEquals(OrderState.NEW, illegal.getOldState());
        assertEquals(EnumSet.of(OrderState.PAID), illegal.getExpectedStates());
        assertEquals(FailureReason.ILLEGAL_TRANSITION, illegal.getReason());
        // the illegal row did not advance state: paid and sent afterwards were accepted
        assertEquals(OrderState.SENT, result.getStates().get("a"));
    }

    @Test
    void exactAcceptanceScenario() throws IOException {
        // order a: new, paid, sent pass; new -> sent rejected and state stays new
        final Result<String, OrderState> result = validate("orders-illegal-transition.csv");
        final IllegalRow<OrderState> illegal = result.getIllegalRows().get(0);
        assertEquals(OrderState.NEW, illegal.getOldState());
        assertEquals(EnumSet.of(OrderState.PAID), illegal.getExpectedStates());
        // after the rejected new->sent, state is still NEW, so new->paid is accepted
        assertEquals(OrderState.SENT, result.getStates().get("a"));
    }

    @Test
    void capacityFailureDoesNotChangeExistingStates() throws IOException {
        // maxKeys=1: a=new (ok), b=new (capacity rejected), a=paid (ok)
        final Result<String, OrderState> result = validate("orders-capacity.csv", 1);
        assertFalse(result.isSuccess());
        assertEquals(1, result.getIllegalRows().size());
        final IllegalRow<OrderState> illegal = result.getIllegalRows().get(0);
        assertEquals(2, illegal.getRecordNumber());
        assertEquals(FailureReason.CAPACITY, illegal.getReason());
        // existing state of a is unchanged and still advances; b is not added
        assertEquals(OrderState.PAID, result.getStates().get("a"));
        assertFalse(result.getStates().containsKey("b"));
    }

    @Test
    void capacityFailureDoesNotEvictActiveStates() throws IOException {
        // maxKeys=1: a=new (ok), b=new (capacity rejected, a must remain)
        final Result<String, OrderState> result = validate("orders-capacity.csv", 1);
        assertEquals(1, result.getStates().size());
        assertEquals(OrderState.PAID, result.getStates().get("a"));
    }

    @Test
    void fieldConversionFailureLeavesStateUnchanged() throws IOException {
        // a: new (ok), xyz (field conversion fail, stays new), paid (ok from new)
        final Result<String, OrderState> result = validate("orders-bad-field.csv");
        assertFalse(result.isSuccess());
        assertEquals(1, result.getIllegalRows().size());
        final IllegalRow<OrderState> illegal = result.getIllegalRows().get(0);
        assertEquals(2, illegal.getRecordNumber());
        assertEquals(OrderState.NEW, illegal.getOldState());
        assertEquals(EnumSet.of(OrderState.PAID), illegal.getExpectedStates());
        assertEquals(FailureReason.FIELD_CONVERSION, illegal.getReason());
        // the bad row did not advance state: paid afterwards was accepted from NEW
        assertEquals(OrderState.PAID, result.getStates().get("a"));
    }

    @Test
    void illegalRowCarriesRecordNumberOldStateAndExpectedSet() throws IOException {
        final Result<String, OrderState> result = validate("orders-illegal-transition.csv");
        final IllegalRow<OrderState> illegal = result.getIllegalRows().get(0);
        assertEquals(2, illegal.getRecordNumber());
        assertEquals(OrderState.NEW, illegal.getOldState());
        assertEquals(EnumSet.of(OrderState.PAID), illegal.getExpectedStates());
        assertEquals(FailureReason.ILLEGAL_TRANSITION, illegal.getReason());
    }

    @Test
    void readOnlyDoesNotWriteBusinessData() throws IOException {
        final Path path = FIXTURES.resolve("orders-interleaved.csv");
        final String before = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        validate("orders-interleaved.csv");
        final String after = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertEquals(before, after);
    }

    @Test
    void emptyKeyIsFieldConversionFailure() throws IOException {
        final Path file = FIXTURES.resolve("orders-interleaved.csv");
        final String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("a,new", ",new");
        final Path tmp = Files.createTempFile("orders-empty-key", ".csv");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try (Reader reader = Files.newBufferedReader(tmp, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                        .parse(reader)) {
            final Result<String, OrderState> result = validator(Integer.MAX_VALUE).validate(parser);
            assertFalse(result.isSuccess());
            assertEquals(FailureReason.FIELD_CONVERSION, result.getIllegalRows().get(0).getReason());
            assertNull(result.getIllegalRows().get(0).getOldState());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
