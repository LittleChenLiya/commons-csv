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

package org.apache.commons.csv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A declarative, compiled projection over the records of a CSV file.
 *
 * <p>
 * A projection declares which columns to select by header name, an optional 0-based occurrence index for duplicated
 * header names, and an optional output alias. It is compiled once against the header names of a {@link CSVParser} (or
 * an explicit header list) and can then be applied record by record, for example through
 * {@link CSVParser#stream()}, without loading the whole file into memory:
 * </p>
 *
 * <pre>
 * CSVProjection projection = CSVProjection.builder()
 *     .column("id")
 *     .column("name", 1, "secondName")
 *     .compile(parser);
 * parser.stream().map(projection::project).forEach(this::consume);
 * </pre>
 *
 * <p>
 * Compilation reads only the header names, which {@link CSVParser} parses in its constructor; a failed compilation
 * therefore never consumes the first data record.
 * </p>
 *
 * <p>
 * Header matching honors an explicit {@link CasePolicy}; no trimming is applied to header names or values beyond what
 * the {@link CSVFormat} already performs. Three distinct irregular situations are configured independently:
 * </p>
 *
 * <ul>
 * <li>a selected header is absent from the header line ({@link Builder#onMissingHeader});</li>
 * <li>a record is shorter than the selected column index ({@link Builder#onMissingColumn});</li>
 * <li>a present column holds the empty string ({@link Builder#onEmpty}), which is preserved as-is by default.</li>
 * </ul>
 *
 * <p>
 * Output aliases must be unique; a duplicated alias fails the build. When a header name occurs multiple times and no
 * occurrence index is given, compilation fails with an ambiguity error.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVProjection {

    /**
     * How header names are matched when compiling a projection.
     */
    public enum CasePolicy {

        /** Header names must match exactly. */
        SENSITIVE,

        /** Header names match ignoring case, using {@link String#equalsIgnoreCase(String)}. */
        INSENSITIVE
    }

    /**
     * How a present column holding the empty string is projected.
     */
    public enum EmptyPolicy {

        /** Keep the empty string as-is. This is the default. */
        PRESERVE,

        /** Throw a {@link CSVProjectionException}. */
        ERROR,

        /** Project null. */
        NULL,

        /** Project a fixed default value. */
        DEFAULT
    }

    /**
     * How a missing header (compile time) or a missing column in a record (projection time) is handled.
     */
    public enum MissingPolicy {

        /** Fail: throw at compile time for a missing header, or a {@link CSVProjectionException} for a missing column. */
        ERROR,

        /** Project null. */
        NULL,

        /** Project a fixed default value. */
        DEFAULT
    }

    /**
     * A compiled column: either a source index into the record values or a constant value.
     */
    private static final class CompiledColumn {

        /** The source header name as declared. */
        final String header;

        /** The output alias. */
        final String alias;

        /** The index into the record values, or -1 when {@link #constant} applies. */
        final int index;

        /** The constant value to project, or null when unset. */
        final String constant;

        CompiledColumn(final String header, final String alias, final int index, final String constant) {
            this.header = header;
            this.alias = alias;
            this.index = index;
            this.constant = constant;
        }
    }

    /**
     * A declared column selection.
     */
    private static final class ColumnSpec {

        /** The source header name. */
        final String header;

        /** The 0-based occurrence index among duplicated headers, or null when unspecified. */
        final Integer occurrence;

        /** The output alias, or null to default to the header name. */
        final String alias;

        ColumnSpec(final String header, final Integer occurrence, final String alias) {
            this.header = header;
            this.occurrence = occurrence;
            this.alias = alias;
        }
    }

    /**
     * Builds and compiles {@link CSVProjection} instances.
     */
    public static final class Builder {

        private final List<ColumnSpec> columns = new ArrayList<>();

        private CasePolicy casePolicy = CasePolicy.SENSITIVE;

        private MissingPolicy missingHeaderPolicy = MissingPolicy.ERROR;

        private String missingHeaderDefault;

        private MissingPolicy missingColumnPolicy = MissingPolicy.ERROR;

        private String missingColumnDefault;

        private EmptyPolicy emptyPolicy = EmptyPolicy.PRESERVE;

        private String emptyDefault;

        private Builder() {
        }

        /**
         * Selects the first occurrence of the given header, aliased to the header name itself.
         *
         * @param header the header name to select.
         * @return this builder.
         */
        public Builder column(final String header) {
            return column(header, null, null);
        }

        /**
         * Selects the first occurrence of the given header under an output alias.
         *
         * @param header the header name to select.
         * @param alias the output alias.
         * @return this builder.
         */
        public Builder column(final String header, final String alias) {
            return column(header, null, alias);
        }

        /**
         * Selects the given 0-based occurrence of the given header, aliased to the header name itself.
         *
         * @param header the header name to select.
         * @param occurrence the 0-based occurrence index among duplicated header names.
         * @return this builder.
         */
        public Builder column(final String header, final int occurrence) {
            return column(header, Integer.valueOf(occurrence), null);
        }

        /**
         * Selects the given 0-based occurrence of the given header under an output alias.
         *
         * @param header the header name to select.
         * @param occurrence the 0-based occurrence index among duplicated header names.
         * @param alias the output alias.
         * @return this builder.
         */
        public Builder column(final String header, final int occurrence, final String alias) {
            return column(header, Integer.valueOf(occurrence), alias);
        }

        private Builder column(final String header, final Integer occurrence, final String alias) {
            Objects.requireNonNull(header, "header");
            if (occurrence != null && occurrence.intValue() < 0) {
                throw new IllegalArgumentException("occurrence must be >= 0: " + occurrence);
            }
            columns.add(new ColumnSpec(header, occurrence, alias));
            return this;
        }

        /**
         * Sets how header names are matched. Defaults to {@link CasePolicy#SENSITIVE}.
         *
         * @param casePolicy the case policy.
         * @return this builder.
         */
        public Builder casePolicy(final CasePolicy casePolicy) {
            this.casePolicy = Objects.requireNonNull(casePolicy, "casePolicy");
            return this;
        }

        /**
         * Compiles the projection against the header names of the given parser.
         *
         * <p>
         * Only the already parsed header names are read; no data record is consumed, so a failed compilation leaves
         * the parser positioned before the first data record.
         * </p>
         *
         * @param parser the parser providing the header names.
         * @return the compiled projection.
         * @throws IllegalArgumentException if the projection is invalid for the parser headers.
         */
        public CSVProjection compile(final CSVParser parser) {
            return compile(parser.getHeaderNames());
        }

        /**
         * Compiles the projection against the given header names.
         *
         * @param headerNames the header names, in column order.
         * @return the compiled projection.
         * @throws IllegalArgumentException if the projection is invalid for the given headers.
         */
        public CSVProjection compile(final List<String> headerNames) {
            Objects.requireNonNull(headerNames, "headerNames");
            final List<CompiledColumn> compiled = new ArrayList<>(columns.size());
            final List<String> aliases = new ArrayList<>(columns.size());
            final Set<String> seenAliases = new HashSet<>();
            for (final ColumnSpec spec : columns) {
                final String alias = spec.alias != null ? spec.alias : spec.header;
                if (alias.isEmpty()) {
                    throw new IllegalArgumentException("Output alias must not be empty for header '" + spec.header + "'");
                }
                if (!seenAliases.add(alias)) {
                    throw new IllegalArgumentException("Duplicate output alias '" + alias + "'");
                }
                compiled.add(compileColumn(spec, alias, headerNames));
                aliases.add(alias);
            }
            return new CSVProjection(compiled, aliases, this);
        }

        /**
         * Compiles the projection against the given header names.
         *
         * @param headerNames the header names, in column order.
         * @return the compiled projection.
         * @throws IllegalArgumentException if the projection is invalid for the given headers.
         */
        public CSVProjection compile(final String... headerNames) {
            final List<String> list = new ArrayList<>(headerNames.length);
            Collections.addAll(list, headerNames);
            return compile(list);
        }

        private CompiledColumn compileColumn(final ColumnSpec spec, final String alias, final List<String> headerNames) {
            final List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < headerNames.size(); i++) {
                final String headerName = headerNames.get(i);
                final boolean match = casePolicy == CasePolicy.INSENSITIVE ? spec.header.equalsIgnoreCase(headerName)
                        : spec.header.equals(headerName);
                if (match) {
                    matches.add(Integer.valueOf(i));
                }
            }
            final Integer index;
            if (spec.occurrence != null) {
                index = spec.occurrence.intValue() < matches.size() ? matches.get(spec.occurrence.intValue()) : null;
            } else if (matches.size() > 1) {
                throw new IllegalArgumentException("Ambiguous header '" + spec.header + "' matches " + matches.size()
                        + " columns " + matches + "; specify a 0-based occurrence index");
            } else {
                index = matches.isEmpty() ? null : matches.get(0);
            }
            if (index != null) {
                return new CompiledColumn(spec.header, alias, index.intValue(), null);
            }
            switch (missingHeaderPolicy) {
            case NULL:
                return new CompiledColumn(spec.header, alias, -1, null);
            case DEFAULT:
                return new CompiledColumn(spec.header, alias, -1, missingHeaderDefault);
            case ERROR:
            default:
                throw new IllegalArgumentException("Header '" + spec.header + "' (alias '" + alias + "') not found in " + headerNames);
            }
        }

        /**
         * Sets how a present column holding the empty string is projected. Defaults to {@link EmptyPolicy#PRESERVE}.
         *
         * @param emptyPolicy the empty value policy.
         * @return this builder.
         */
        public Builder onEmpty(final EmptyPolicy emptyPolicy) {
            return onEmpty(emptyPolicy, null);
        }

        /**
         * Sets how a present column holding the empty string is projected, with a fixed default value used when the
         * policy is {@link EmptyPolicy#DEFAULT}.
         *
         * @param emptyPolicy the empty value policy.
         * @param defaultValue the fixed default value for {@link EmptyPolicy#DEFAULT}.
         * @return this builder.
         */
        public Builder onEmpty(final EmptyPolicy emptyPolicy, final String defaultValue) {
            this.emptyPolicy = Objects.requireNonNull(emptyPolicy, "emptyPolicy");
            this.emptyDefault = defaultValue;
            return this;
        }

        /**
         * Sets how a record that lacks the selected column is handled. Defaults to {@link MissingPolicy#ERROR}.
         *
         * @param missingColumnPolicy the missing column policy.
         * @return this builder.
         */
        public Builder onMissingColumn(final MissingPolicy missingColumnPolicy) {
            return onMissingColumn(missingColumnPolicy, null);
        }

        /**
         * Sets how a record that lacks the selected column is handled, with a fixed default value used when the policy
         * is {@link MissingPolicy#DEFAULT}.
         *
         * @param missingColumnPolicy the missing column policy.
         * @param defaultValue the fixed default value for {@link MissingPolicy#DEFAULT}.
         * @return this builder.
         */
        public Builder onMissingColumn(final MissingPolicy missingColumnPolicy, final String defaultValue) {
            this.missingColumnPolicy = Objects.requireNonNull(missingColumnPolicy, "missingColumnPolicy");
            this.missingColumnDefault = defaultValue;
            return this;
        }

        /**
         * Sets how a selected header that is absent from the header line is handled at compile time. Defaults to
         * {@link MissingPolicy#ERROR}.
         *
         * @param missingHeaderPolicy the missing header policy.
         * @return this builder.
         */
        public Builder onMissingHeader(final MissingPolicy missingHeaderPolicy) {
            return onMissingHeader(missingHeaderPolicy, null);
        }

        /**
         * Sets how a selected header that is absent from the header line is handled at compile time, with a fixed
         * default value used when the policy is {@link MissingPolicy#DEFAULT}.
         *
         * @param missingHeaderPolicy the missing header policy.
         * @param defaultValue the fixed default value for {@link MissingPolicy#DEFAULT}.
         * @return this builder.
         */
        public Builder onMissingHeader(final MissingPolicy missingHeaderPolicy, final String defaultValue) {
            this.missingHeaderPolicy = Objects.requireNonNull(missingHeaderPolicy, "missingHeaderPolicy");
            this.missingHeaderDefault = defaultValue;
            return this;
        }
    }

    /**
     * Creates a new projection builder.
     *
     * @return a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final CompiledColumn[] columns;

    private final List<String> aliases;

    private final MissingPolicy missingColumnPolicy;

    private final String missingColumnDefault;

    private final EmptyPolicy emptyPolicy;

    private final String emptyDefault;

    private CSVProjection(final List<CompiledColumn> columns, final List<String> aliases, final Builder builder) {
        this.columns = columns.toArray(new CompiledColumn[0]);
        this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases));
        this.missingColumnPolicy = builder.missingColumnPolicy;
        this.missingColumnDefault = builder.missingColumnDefault;
        this.emptyPolicy = builder.emptyPolicy;
        this.emptyDefault = builder.emptyDefault;
    }

    /**
     * Gets the output aliases of this projection, in projection order.
     *
     * @return the output aliases.
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * Projects a single record.
     *
     * @param record the record to project.
     * @return the projected record, preserving the source record number and positions.
     * @throws CSVProjectionException if a configured policy fails for this record.
     */
    public ProjectedRecord project(final CSVRecord record) {
        final String[] values = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            final CompiledColumn column = columns[i];
            String value;
            if (column.index < 0) {
                value = column.constant;
            } else if (column.index >= record.size()) {
                switch (missingColumnPolicy) {
                case NULL:
                    value = null;
                    break;
                case DEFAULT:
                    value = missingColumnDefault;
                    break;
                case ERROR:
                default:
                    throw failure(record, column, "Record " + record.getRecordNumber() + " has only " + record.size()
                            + " values, column '" + column.header + "' requires index " + column.index);
                }
            } else {
                value = record.get(column.index);
            }
            if (value != null && value.isEmpty() && emptyPolicy != EmptyPolicy.PRESERVE) {
                switch (emptyPolicy) {
                case NULL:
                    value = null;
                    break;
                case DEFAULT:
                    value = emptyDefault;
                    break;
                case ERROR:
                default:
                    throw failure(record, column, "Record " + record.getRecordNumber() + " has an empty value for column '" + column.header + "'");
                }
            }
            values[i] = value;
        }
        return new ProjectedRecord(aliases, values, record.getRecordNumber(), record.getCharacterPosition(), record.getBytePosition());
    }

    private CSVProjectionException failure(final CSVRecord record, final CompiledColumn column, final String message) {
        return new CSVProjectionException(record.getRecordNumber(), column.header, column.alias, message);
    }

    /**
     * Returns the number of projected columns.
     *
     * @return the number of projected columns.
     */
    public int size() {
        return columns.length;
    }
}
