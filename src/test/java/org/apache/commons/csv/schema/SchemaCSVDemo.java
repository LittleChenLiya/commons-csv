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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.csv.CSVFormat;

/**
 * Small command-line demonstration of {@link SchemaCSVReader}.
 *
 * <p>It reads a CSV file split into schema sections and prints, for every
 * business record, its section number, its record numbers and each bound
 * field value. With no argument it parses the bundled
 * {@code src/test/resources/schema/schema-sections.csv} fixture.</p>
 *
 * <p>Run (class path example) with the compiled classes, Commons IO and
 * Commons Codec on the class path:</p>
 * <pre>
 * java org.apache.commons.csv.schema.SchemaCSVDemo [path/to/file.csv]
 * </pre>
 */
public final class SchemaCSVDemo {

    private static final Path DEFAULT_FIXTURE = Paths.get("src", "test", "resources", "schema", "schema-sections.csv");

    private SchemaCSVDemo() {
    }

    public static void main(final String[] args) throws IOException {
        final Path path = args.length > 0 ? Paths.get(args[0]) : DEFAULT_FIXTURE;
        final CSVFormat format = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                SchemaCSVReader schemaReader = new SchemaCSVReader(reader, format)) {
            System.out.println("Reading " + path.toAbsolutePath());
            for (final SchemaRecord record : schemaReader) {
                final StringBuilder line = new StringBuilder();
                line.append("section=").append(record.getSection());
                line.append(" record=").append(record.getRecordNumber());
                line.append(" sectionRecord=").append(record.getSectionRecordNumber());
                line.append(" fields={");
                boolean first = true;
                for (final String column : record.getHeaderNames()) {
                    if (!first) {
                        line.append(", ");
                    }
                    line.append(column).append('=').append(record.get(column));
                    first = false;
                }
                line.append('}');
                if (record.getComment() != null) {
                    line.append(" comment=\"").append(record.getComment()).append('"');
                }
                System.out.println(line);
            }
            if (schemaReader.getTrailerComment() != null) {
                System.out.println("trailer comment=\"" + schemaReader.getTrailerComment() + '"');
            }
            System.out.println("Done. Final section: " + schemaReader.getSection());
        } catch (final IOException | UncheckedIOException e) {
            final Throwable cause = e.getCause() instanceof SchemaCSVException ? e.getCause() : e;
            if (cause instanceof SchemaCSVException) {
                final SchemaCSVException se = (SchemaCSVException) cause;
                System.err.println("Schema error " + se.getError() + " at section " + se.getSection()
                        + ", record " + se.getRecordNumber() + ": " + se.getMessage());
                System.exit(2);
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw e;
        }
    }
}
