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

package org.apache.commons.csv.schema;

/**
 * Kinds of structural problems detected by {@link SchemaCSVReader}.
 *
 * <p>Every error is located by the 1-based section number ({@link #getSection()})
 * and the 1-based physical CSV record number within the stream
 * ({@link #getRecordNumber()}).</p>
 *
 * @since 1.15.0
 */
public enum SchemaCSVError {

    /**
     * A data record appeared before any {@code __schema__} declaration had installed
     * a header for the current section.
     */
    DATA_BEFORE_HEADER,

    /**
     * A {@code __schema__} declaration listed the same column name more than once,
     * or reused the reserved name {@code __schema__} as a data column.
     */
    DUPLICATE_COLUMN,

    /**
     * A data record contained a different number of fields than the header
     * installed for its section.
     */
    COLUMN_COUNT_MISMATCH,

    /**
     * A further read was attempted after the reader had already entered its
     * terminal state because of an earlier error.
     */
    TERMINATED
}
