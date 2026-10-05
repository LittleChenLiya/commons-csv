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
import java.util.List;
import java.util.Map;

/**
 * The immutable outcome of a {@link CsvSequenceValidator} run: the still-active key states plus every rejection in
 * input order.
 *
 * @since 1.15.0
 */
public final class ValidationResult {

    private final Map<String, String> activeStates;
    private final List<Rejection> rejections;

    ValidationResult(final Map<String, String> activeStates, final List<Rejection> rejections) {
        this.activeStates = Collections.unmodifiableMap(new LinkedHashMap<>(activeStates));
        this.rejections = Collections.unmodifiableList(new ArrayList<>(rejections));
    }

    /**
     * Gets the states of keys still active (accepted and not yet in a final state) at end of input.
     *
     * @return key to current state, in first-acceptance order.
     */
    public Map<String, String> getActiveStates() {
        return activeStates;
    }

    /**
     * Gets the current state of a key.
     *
     * @param key the business key.
     * @return its active state, or null if the key is unknown or already reached a final state.
     */
    public String getState(final String key) {
        return activeStates.get(key);
    }

    /**
     * Gets the number of active keys, never exceeding the configured capacity.
     *
     * @return the active key count.
     */
    public int getActiveKeyCount() {
        return activeStates.size();
    }

    /**
     * Gets all rejections in input order.
     *
     * @return the rejections.
     */
    public List<Rejection> getRejections() {
        return rejections;
    }

    /**
     * Tests whether every record was accepted.
     *
     * @return true if there were no rejections.
     */
    public boolean isValid() {
        return rejections.isEmpty();
    }
}
