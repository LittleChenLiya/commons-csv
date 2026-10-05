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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CsvSequenceValidator} against the order life cycle new -&gt; paid -&gt; sent.
 */
class CsvSequenceValidatorTest {

    private static CsvSequenceValidator orderValidator(final int maxActiveKeys) {
        return CsvSequenceValidator.builder()
            .keyColumn("order")
            .stateColumn("event")
            .initialStates("new")
            .transition("new", "paid")
            .transition("paid", "sent")
            .finalStates("sent")
            .maxActiveKeys(maxActiveKeys)
            .fieldConverter("amount", raw -> Integer.valueOf(raw))
            .build();
    }

    private static ValidationResult validate(final CsvSequenceValidator validator, final Reader reader)
            throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = new CSVParser(reader, format)) {
            return validator.validate(parser);
        }
    }

    private static ValidationResult validateString(final CsvSequenceValidator validator, final String csv)
            throws IOException {
        return validate(validator, new StringReader(csv));
    }

    @Test
    void interleavedOrdersEachTrackTheirOwnState() throws IOException {
        final ClassLoader cl = CsvSequenceValidatorTest.class.getClassLoader();
        final ValidationResult result;
        try (Reader reader = new InputStreamReader(
                cl.getResourceAsStream("org/apache/commons/csv/sequence/orders-interleaved.csv"),
                StandardCharsets.UTF_8)) {
            result = validate(orderValidator(2), reader);
        }
        assertTrue(result.isValid(), () -> "unexpected rejections: " + result.getRejections());
        // Both orders reached the final state "sent", so no key stays active.
        assertEquals(0, result.getActiveKeyCount());
    }

    @Test
    void illegalJumpIsRejectedAndDoesNotAdvanceState() throws IOException {
        final ValidationResult result = validateString(orderValidator(4),
            "order,event,amount\n"
            + "a,new,10\n"
            + "a,sent,10\n"
            + "a,paid,10\n");
        assertEquals(1, result.getRejections().size());
        final Rejection rejection = result.getRejections().get(0);
        assertEquals(2, rejection.getRecordNumber());
        assertEquals("a", rejection.getKey());
        assertEquals("new", rejection.getOldState());
        assertEquals("sent", rejection.getAttemptedState());
        assertEquals(Collections.singleton("paid"), rejection.getExpectedStates());
        assertEquals(RejectionReason.ILLEGAL_TRANSITION, rejection.getReason());
        // The rejected row did not advance the state: the following paid was accepted from new.
        assertEquals("paid", result.getState("a"));
    }

    @Test
    void keysTrackStateIndependently() throws IOException {
        final ValidationResult result = validateString(orderValidator(4),
            "order,event,amount\n"
            + "a,new,10\n"
            + "b,paid,20\n"
            + "a,paid,10\n");
        assertEquals(1, result.getRejections().size());
        final Rejection rejection = result.getRejections().get(0);
        assertEquals(2, rejection.getRecordNumber());
        assertEquals("b", rejection.getKey());
        assertNull(rejection.getOldState());
        assertEquals(Collections.singleton("new"), rejection.getExpectedStates());
        assertEquals(RejectionReason.ILLEGAL_TRANSITION, rejection.getReason());
        // a is unaffected by b's rejection.
        assertEquals("paid", result.getState("a"));
        assertNull(result.getState("b"));
    }

    @Test
    void capacityOverflowRejectsWithoutEvictingActiveStates() throws IOException {
        final ValidationResult result = validateString(orderValidator(1),
            "order,event,amount\n"
            + "a,new,10\n"
            + "b,new,20\n"
            + "a,paid,10\n"
            + "a,sent,10\n"
            + "b,new,20\n");
        assertEquals(1, result.getRejections().size());
        final Rejection rejection = result.getRejections().get(0);
        assertEquals(2, rejection.getRecordNumber());
        assertEquals("b", rejection.getKey());
        assertEquals(RejectionReason.CAPACITY_EXCEEDED, rejection.getReason());
        assertTrue(rejection.getExpectedStates().isEmpty());
        // The capacity failure did not change a's state: a kept progressing normally,
        // and once a reached the final state its slot freed, so b's retry was accepted.
        assertEquals("new", result.getState("b"));
        assertNull(result.getState("a"));
        assertEquals(1, result.getActiveKeyCount());
    }

    @Test
    void fieldConversionFailureBlocksStateUpdate() throws IOException {
        final ValidationResult result = validateString(orderValidator(4),
            "order,event,amount\n"
            + "a,new,10\n"
            + "a,paid,not-a-number\n"
            + "a,sent,10\n");
        assertEquals(2, result.getRejections().size());
        final Rejection conversion = result.getRejections().get(0);
        assertEquals(2, conversion.getRecordNumber());
        assertEquals("a", conversion.getKey());
        assertEquals("new", conversion.getOldState());
        assertEquals(RejectionReason.FIELD_CONVERSION, conversion.getReason());
        assertEquals(Collections.singleton("paid"), conversion.getExpectedStates());
        // Because the paid row never landed, the sent row is still illegal from new.
        final Rejection stillIllegal = result.getRejections().get(1);
        assertEquals(3, stillIllegal.getRecordNumber());
        assertEquals("new", stillIllegal.getOldState());
        assertEquals(RejectionReason.ILLEGAL_TRANSITION, stillIllegal.getReason());
        assertEquals("new", result.getState("a"));
    }
}
