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

/**
 * Section-aware wrapper for {@link org.apache.commons.csv.CSVParser}.
 *
 * <p>
 * A CSV stream is split into <em>sections</em>. Every section is introduced by a
 * <em>schema declaration</em>: a record whose first field is the reserved marker
 * {@link org.apache.commons.csv.schema.SchemaCSVReader#SCHEMA __schema__}. The remaining
 * fields of the declaration name the columns of the new section. For example:
 * </p>
 *
 * <pre>
 * __schema__,name,age
 * a,1
 * __schema__,age,name
 * 2,b
 * </pre>
 *
 * <p>
 * The declaration record itself is consumed by the reader and is never delivered to
 * business code. Every following data record is bound to the header of the section it
 * belongs to, so {@code get("name")} resolves against the column names of the current
 * section. A new {@code __schema__} declaration switches to a new section with its own
 * header; the previous section is left behind.
 * </p>
 *
 * <h2>Termination</h2>
 * <p>
 * If a declaration is invalid (for example it declares a duplicate column name), if a
 * data record appears before any declaration, or if a data record's field count does not
 * match the section's header, the reader enters a <em>terminated</em> state. No record is
 * delivered after that point, {@code hasNext()} returns {@code false}, and every
 * {@code next()} call reports the termination. This prevents later data from being bound
 * to a stale header.
 * </p>
 *
 * <h2>Parsing rules</h2>
 * <p>
 * Comments and quoted fields are parsed exactly as configured by the underlying
 * {@link org.apache.commons.csv.CSVFormat}; the wrapper only interprets the decoded field
 * values. In particular, the {@code __schema__} marker is recognized by its decoded value
 * (quotes do not change its identity), not by reference.
 * </p>
 *
 * <p>
 * Empty sections are allowed: a declaration may be followed immediately by another
 * declaration or by end of stream without producing any data record.
 * </p>
 *
 * @see org.apache.commons.csv.schema.SchemaCSVReader
 * @see org.apache.commons.csv.schema.SchemaRecord
 */
package org.apache.commons.csv.schema;
