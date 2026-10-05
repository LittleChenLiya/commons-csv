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

/**
 * CSV sequence validation: maintains a caller-declared finite state machine per business key over a read-only CSV
 * record stream. See {@link org.apache.commons.csv.sequence.CsvSequenceValidator} for the full contract, and
 * {@code docs/csv-sequence-validator-contract.md} for a narrative description.
 *
 * @since 1.15.0
 */
package org.apache.commons.csv.sequence;
