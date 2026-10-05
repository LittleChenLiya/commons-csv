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

/**
 * Converts (and thereby validates) a single raw CSV field value.
 *
 * <p>
 * Implementations act as a gate: a record whose fields all convert successfully is eligible for a state transition;
 * a record with any failing conversion is rejected with {@link RejectionReason#FIELD_CONVERSION} and never advances
 * its key's state. Converted values are discarded by the validator, which is read-only with respect to business data.
 * </p>
 *
 * @since 1.15.0
 */
@FunctionalInterface
public interface FieldConverter {

    /**
     * Converts a raw field value.
     *
     * @param rawValue the raw CSV field value, never null for present columns.
     * @return the converted value; the validator discards it.
     * @throws RuntimeException (typically {@link IllegalArgumentException}) if the value cannot be converted.
     */
    Object convert(String rawValue);
}
