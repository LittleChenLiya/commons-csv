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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable description of a single rejected CSV record.
 *
 * <p>
 * A rejection always carries the 1-based record number (as reported by
 * {@link org.apache.commons.csv.CSVRecord#getRecordNumber()}), the business key, the key's state as observed
 * <em>before</em> the record (null for a key never accepted), the attempted target state, and the set of states that
 * would have been accepted instead. A rejected record never changes any key's state.
 * </p>
 *
 * @since 1.15.0
 */
public final class Rejection {

    private final long recordNumber;
    private final String key;
    private final String oldState;
    private final String attemptedState;
    private final Set<String> expectedStates;
    private final RejectionReason reason;
    private final String detail;

    Rejection(final long recordNumber, final String key, final String oldState, final String attemptedState,
            final Set<String> expectedStates, final RejectionReason reason, final String detail) {
        this.recordNumber = recordNumber;
        this.key = key;
        this.oldState = oldState;
        this.attemptedState = attemptedState;
        this.expectedStates = Collections.unmodifiableSet(new LinkedHashSet<>(expectedStates));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.detail = detail;
    }

    /**
     * Gets the 1-based record number within the parsed CSV input.
     *
     * @return the record number.
     */
    public long getRecordNumber() {
        return recordNumber;
    }

    /**
     * Gets the business key of the rejected record.
     *
     * @return the key.
     */
    public String getKey() {
        return key;
    }

    /**
     * Gets the key's state before the rejected record.
     *
     * @return the old state, or null if the key had no accepted state yet.
     */
    public String getOldState() {
        return oldState;
    }

    /**
     * Gets the target state the rejected record tried to reach.
     *
     * @return the attempted state.
     */
    public String getAttemptedState() {
        return attemptedState;
    }

    /**
     * Gets the states that would have been accepted for this key at this point. Empty when no state could have been
     * accepted (for example, capacity exhausted for a new key).
     *
     * @return the expected state set, in declaration order.
     */
    public Set<String> getExpectedStates() {
        return expectedStates;
    }

    /**
     * Gets why the record was rejected.
     *
     * @return the reason.
     */
    public RejectionReason getReason() {
        return reason;
    }

    /**
     * Gets an optional detail message, for example the failure message of a {@link FieldConverter}.
     *
     * @return the detail, or null.
     */
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Rejection[record=").append(recordNumber);
        sb.append(", key=").append(key);
        sb.append(", oldState=").append(oldState);
        sb.append(", attempted=").append(attemptedState);
        sb.append(", expected=").append(expectedStates);
        sb.append(", reason=").append(reason);
        if (detail != null) {
            sb.append(", detail=").append(detail);
        }
        return sb.append(']').toString();
    }
}
