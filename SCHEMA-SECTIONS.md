<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Schema sections (`org.apache.commons.csv.schema`)

A small, self-contained wrapper reader that lets **one CSV stream contain
several sections**, each with its own column header. It is implemented purely
on top of `CSVParser`; the CSV grammar (delimiters, quoting, comments, empty
lines, trimming, ...) is unchanged and is still configured through the usual
`CSVFormat`.

## The reserved `__schema__` record

A record whose **first decoded field** is exactly `__schema__` is a schema
declaration. It is consumed by the wrapper and is **never delivered to
business code**. The remaining fields are the column names that bind every
following record, up to the next declaration:

```csv
__schema__,name,age
a,1
__schema__,age,name
2,b
```

The two data records are bound by name to *their own* header, so after the
second declaration `age` is column 0 and `name` is column 1:

| physical record | section | `get("name")` | `get("age")` |
|---|---|---|---|
| 2 | 1 | `a` | `1` |
| 4 | 2 | `b` | `2` |

### Identity is the decoded value

`__schema__` is a reserved **value**, not a piece of surface syntax:

* `"__schema__"` (quoted) decodes to `__schema__`, so it is still a declaration.
* `" __schema__"` (a leading space in the *decoded* value) is ordinary data.
* If the format trims surrounding spaces, trimming happens first and the
  trimmed decoded value is what matters.

## Sections and numbering

* The stream logically starts in **section 1 with no header**. The first
  declaration opens section 1, the second opens section 2, and so on.
* A data record that appears before the current section has a valid header is
  rejected.
* **Empty sections are allowed**: two consecutive declarations simply open an
  empty section followed by a fresh one.
* Every delivered `SchemaRecord` exposes:
  * `getSection()` – 1-based section number.
  * `getRecordNumber()` – 1-based physical CSV record number (declarations are
    counted; matches `CSVRecord.getRecordNumber()`).
  * `getSectionRecordNumber()` – 1-based data-record ordinal inside the section.
  * `get(String)`, `get(int)`, `values()`, `toMap()`, `getHeaderNames()`.

## Errors and the terminal state

Three violations are reported, each carrying the **section number and the
physical record number** in a `SchemaCSVException`
(`getError()`, `getSection()`, `getRecordNumber()`):

| `SchemaCSVError` | Meaning |
|---|---|
| `DATA_BEFORE_HEADER` | Data appeared before any valid header in that section. |
| `DUPLICATE_COLUMN` | A declaration repeats a column name, or declares a data column named `__schema__`. |
| `COLUMN_COUNT_MISMATCH` | A data record does not have exactly as many fields as the header. |
| `TERMINATED` | A read was attempted after an earlier failure. |

When a declaration cannot be installed (or any violation occurs) the reader
enters a **terminal state**: the previous header is dropped so that later
records can never be bound against it, and every subsequent `next()` keeps
throwing `TERMINATED` with the original exception as its cause.

```java
SchemaRecord r;
try {
    while ((r = reader.next()) != null) { ... }
} catch (SchemaCSVException e) {
    // first failure: e.getError() is the concrete violation
    // every later call throws SchemaCSVError.TERMINATED with this as cause
}
```

## Comments and quoting

Comments (when `setCommentMarker(...)` is used) and quoted fields are parsed by
the underlying `CSVParser` exactly as normal. Comment lines do not become data;
a comment immediately before a record is available from
`SchemaRecord.getComment()`, and a trailing comment at end of stream from
`SchemaCSVReader.getTrailerComment()`.

Any `setHeader(...)` / `setSkipHeaderRecord(...)` options on the supplied format
are ignored, because headers come from the in-stream declarations; all other
format options are preserved.

## Usage

```java
CSVFormat format = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();
try (SchemaCSVReader reader = new SchemaCSVReader(csvReader, format)) {
    for (SchemaRecord record : reader) {
        System.out.println(record.getSection()
            + "/" + record.getSectionRecordNumber()
            + " name=" + record.get("name"));
    }
}
```

`SchemaCSVReader.of(String, CSVFormat)` is a convenience for in-memory text.

## Contents of this module

* `SchemaCSVReader` – the wrapper reader (`Closeable`, `Iterable`).
* `SchemaRecord` – one delivered data record, already bound to its header.
* `SchemaCSVException` / `SchemaCSVError` – located errors and the terminal state.
* Tests: `src/test/java/org/apache/commons/csv/schema/SchemaCSVReaderTest.java`
  (re-ordered headers, empty sections, comments, EOF, quoting, reserved-value
  identity, and persistent termination).
* Demo: `src/test/java/org/apache/commons/csv/schema/SchemaCSVDemo.java`, run
  against `src/test/resources/schema/schema-sections.csv`:

  ```bash
  java org.apache.commons.csv.schema.SchemaCSVDemo [path/to/file.csv]
  ```

  It prints each record's section, record numbers and bound field values.
