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
 * Why a CSV record was rejected by a {@link CsvSequenceValidator}.
 *
 * @since 1.15.0
 */
public enum RejectionReason {

    /** A registered {@link FieldConverter} failed for a field of the record. */
    FIELD_CONVERSION,

    /** The record's target state is not in the set of states allowed from the key's current state. */
    ILLEGAL_TRANSITION,

    /** The record introduces a new key while the active-key capacity is exhausted; no active state was evicted. */
    CAPACITY_EXCEEDED
}
