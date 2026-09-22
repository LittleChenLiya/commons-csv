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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A declarative, compiled projection over the records of a CSV document.
 *
 * <p>
 * A projection selects columns by header name, with an optional 0-based occurrence index to disambiguate duplicate
 * header names, and assigns each selected column a unique output alias. The projection is <em>compiled</em> once from
 * the header names (typically right after the parser has read the header, via {@link Builder#compile(CSVParser)}) and
 * is then applied record by record with {@link #project(CSVRecord)}, so the whole file is never loaded into memory.
 * </p>
 *
 * <p>
 * Value lookup semantics follow {@link CSVRecord}: values are returned as stored in the record, no extra trimming of
 * business values is performed (whitespace handling is entirely governed by the {@link CSVFormat} used by the parser),
 * and the original record number, character position and byte position are preserved on the resulting
 * {@link ProjectedRecord}.
 * </p>
 *
 * <p>
 * Three distinct data situations are each handled by their own configurable policy:
 * </p>
 * <ul>
 * <li><b>Missing header</b>: the header row does not contain the requested column at all
 * ({@link Builder#onMissingHeader(MissingHeaderPolicy)}, default {@code ERROR}).</li>
 * <li><b>Missing column</b>: the header exists but a given record is too short to contain the column
 * ({@link Builder#onMissingColumn(MissingColumnPolicy)}, default {@code ERROR}).</li>
 * <li><b>Empty value</b>: the column exists in the record but its value is the empty string
 * ({@link Builder#onEmptyValue(EmptyValuePolicy)}, default {@code KEEP}, which preserves the empty string as-is).</li>
 * </ul>
 *
 * <p>
 * Matching of header names is exact by default; case-insensitive matching must be requested explicitly with
 * {@link Builder#caseSensitivity(CaseSensitivity)}. Requesting a duplicated header name without an occurrence index
 * fails compilation with an ambiguity error. All {@link CSVProjectionException}s carry the original record number, the
 * original column name and the target alias.
 * </p>
 *
 * <p>
 * Compilation only reads the header names; a failed compilation never consumes the first data record of the parser.
 * </p>
 *
 * @since 1.15.0
 */
public final class CSVProjection {

    /**
     * Builds and compiles {@link CSVProjection} instances.
     */
    public static final class Builder {

        private final List<ColumnSpec> columns = new ArrayList<>();

        private final Map<String, String> aliases = new LinkedHashMap<>();

        private CaseSensitivity caseSensitivity = CaseSensitivity.EXACT;

        private MissingHeaderPolicy missingHeaderPolicy = MissingHeaderPolicy.ERROR;

        private String missingHeaderDefault;

        private MissingColumnPolicy missingColumnPolicy = MissingColumnPolicy.ERROR;

        private String missingColumnDefault;

        private EmptyValuePolicy emptyValuePolicy = EmptyValuePolicy.KEEP;

        private String emptyValueDefault;

        private Builder() {
        }

        private Builder addColumn(final String header, final Integer occurrence, final String alias) {
            Objects.requireNonNull(header, "header");
            final String effectiveAlias = alias == null ? header : alias;
            if (effectiveAlias.isEmpty()) {
                throw new IllegalArgumentException("Alias must not be empty");
            }
            if (aliases.put(effectiveAlias, header) != null) {
                throw new IllegalArgumentException("Duplicate alias '" + effectiveAlias + "'");
            }
            if (occurrence != null && occurrence.intValue() < 0) {
                throw new IllegalArgumentException("Occurrence must be >= 0: " + occurrence);
            }
            columns.add(new ColumnSpec(header, occurrence, effectiveAlias));
            return this;
        }

        /**
         * Sets how header names are matched. The default is {@link CaseSensitivity#EXACT}.
         *
         * @param caseSensitivity the matching strategy.
         * @return this builder.
         */
        public Builder caseSensitivity(final CaseSensitivity caseSensitivity) {
            this.caseSensitivity = Objects.requireNonNull(caseSensitivity, "caseSensitivity");
            return this;
        }

        /**
         * Compiles the projection against the header names of the given parser. Only the header names are read; no
         * data record is consumed, even when compilation fails.
         *
         * @param parser the parser whose header names drive the compilation.
         * @return the compiled projection.
         * @throws CSVProjectionException if a column cannot be resolved (unknown header, ambiguous duplicate header).
         */
        public CSVProjection compile(final CSVParser parser) {
            return compile(parser.getHeaderNames());
        }

        /**
         * Compiles the projection against the given header names.
         *
         * @param headerNames the header names, in column order, as reported by the parser (whitespace handling is left
         *        to the {@link CSVFormat}).
         * @return the compiled projection.
         * @throws CSVProjectionException if a column cannot be resolved (unknown header, ambiguous duplicate header).
         */
        public CSVProjection compile(final List<String> headerNames) {
            final List<CompiledColumn> compiled = new ArrayList<>(columns.size());
            final List<String> aliasesOut = new ArrayList<>(columns.size());
            for (final ColumnSpec spec : columns) {
                final List<Integer> matches = new ArrayList<>();
                for (int i = 0; i < headerNames.size(); i++) {
                    if (caseSensitivity.matches(headerNames.get(i), spec.header)) {
                        matches.add(Integer.valueOf(i));
                    }
                }
                if (spec.occurrence == null) {
                    if (matches.size() > 1) {
                        throw new CSVProjectionException(-1, spec.header, spec.alias, "Ambiguous header '" + spec.header + "' occurs " + matches.size()
                                + " times; specify a 0-based occurrence index");
                    }
                    if (matches.isEmpty()) {
                        compiled.add(resolveMissingHeader(spec));
                    } else {
                        compiled.add(CompiledColumn.at(matches.get(0).intValue(), spec));
                    }
                } else if (spec.occurrence.intValue() < matches.size()) {
                    compiled.add(CompiledColumn.at(matches.get(spec.occurrence.intValue()).intValue(), spec));
                } else {
                    compiled.add(resolveMissingHeader(spec));
                }
                aliasesOut.add(spec.alias);
            }
            return new CSVProjection(compiled, Collections.unmodifiableList(aliasesOut), missingColumnPolicy, missingColumnDefault, emptyValuePolicy,
                    emptyValueDefault);
        }

        private CompiledColumn resolveMissingHeader(final ColumnSpec spec) {
            switch (missingHeaderPolicy) {
            case ERROR:
                throw new CSVProjectionException(-1, spec.header, spec.alias, "No such header '" + spec.header + "'");
            case NULL:
                return CompiledColumn.constant(null, spec);
            case DEFAULT:
                return CompiledColumn.constant(missingHeaderDefault, spec);
            default:
                throw new IllegalStateException("Unexpected policy " + missingHeaderPolicy);
            }
        }

        /**
         * Sets the policy for empty-string values (column present, value is {@code ""}). The default is
         * {@link EmptyValuePolicy#KEEP}, which preserves the empty string as-is.
         *
         * @param policy the policy.
         * @return this builder.
         */
        public Builder onEmptyValue(final EmptyValuePolicy policy) {
            return onEmptyValue(policy, null);
        }

        /**
         * Sets the policy for empty-string values, with the fixed default value used when the policy is
         * {@link EmptyValuePolicy#DEFAULT}.
         *
         * @param policy the policy.
         * @param defaultValue the fixed default value, only used by {@link EmptyValuePolicy#DEFAULT}.
         * @return this builder.
         */
        public Builder onEmptyValue(final EmptyValuePolicy policy, final String defaultValue) {
            this.emptyValuePolicy = Objects.requireNonNull(policy, "policy");
            this.emptyValueDefault = defaultValue;
            return this;
        }

        /**
         * Sets the policy for records that are too short to contain a selected column. The default is
         * {@link MissingColumnPolicy#ERROR}.
         *
         * @param policy the policy.
         * @return this builder.
         */
        public Builder onMissingColumn(final MissingColumnPolicy policy) {
            return onMissingColumn(policy, null);
        }

        /**
         * Sets the policy for records that are too short to contain a selected column, with the fixed default value
         * used when the policy is {@link MissingColumnPolicy#DEFAULT}.
         *
         * @param policy the policy.
         * @param defaultValue the fixed default value, only used by {@link MissingColumnPolicy#DEFAULT}.
         * @return this builder.
         */
        public Builder onMissingColumn(final MissingColumnPolicy policy, final String defaultValue) {
            this.missingColumnPolicy = Objects.requireNonNull(policy, "policy");
            this.missingColumnDefault = defaultValue;
            return this;
        }

        /**
         * Sets the policy for selected headers that do not exist in the header row. The default is
         * {@link MissingHeaderPolicy#ERROR}.
         *
         * @param policy the policy.
         * @return this builder.
         */
        public Builder onMissingHeader(final MissingHeaderPolicy policy) {
            return onMissingHeader(policy, null);
        }

        /**
         * Sets the policy for selected headers that do not exist in the header row, with the fixed default value used
         * when the policy is {@link MissingHeaderPolicy#DEFAULT}.
         *
         * @param policy the policy.
         * @param defaultValue the fixed default value, only used by {@link MissingHeaderPolicy#DEFAULT}.
         * @return this builder.
         */
        public Builder onMissingHeader(final MissingHeaderPolicy policy, final String defaultValue) {
            this.missingHeaderPolicy = Objects.requireNonNull(policy, "policy");
            this.missingHeaderDefault = defaultValue;
            return this;
        }

        /**
         * Selects the (unique) column with the given header name; the output alias is the header name itself.
         *
         * @param header the header name.
         * @return this builder.
         */
        public Builder select(final String header) {
            return addColumn(header, null, null);
        }

        /**
         * Selects the column with the given header name and 0-based occurrence index (for duplicate header names);
         * the output alias is the header name itself.
         *
         * @param header the header name.
         * @param occurrence the 0-based occurrence index among columns with that header name.
         * @return this builder.
         */
        public Builder select(final String header, final int occurrence) {
            return addColumn(header, Integer.valueOf(occurrence), null);
        }

        /**
         * Selects the column with the given header name and 0-based occurrence index (for duplicate header names),
         * and assigns it the given output alias.
         *
         * @param header the header name.
         * @param occurrence the 0-based occurrence index among columns with that header name.
         * @param alias the unique output alias.
         * @return this builder.
         */
        public Builder select(final String header, final int occurrence, final String alias) {
            return addColumn(header, Integer.valueOf(occurrence), alias);
        }

        /**
         * Selects the (unique) column with the given header name and assigns it the given output alias.
         *
         * @param header the header name.
         * @param alias the unique output alias.
         * @return this builder.
         */
        public Builder select(final String header, final String alias) {
            return addColumn(header, null, alias);
        }
    }

    /**
     * How header names are matched against requested column names.
     */
    public enum CaseSensitivity {

        /** Header names must match exactly (default). */
        EXACT {
            @Override
            boolean matches(final String actual, final String requested) {
                return actual.equals(requested);
            }
        },

        /** Header names match ignoring case, per explicit opt-in. */
        IGNORE_CASE {
            @Override
            boolean matches(final String actual, final String requested) {
                return actual.equalsIgnoreCase(requested);
            }
        };

        abstract boolean matches(String actual, String requested);
    }

    /**
     * Policy applied when a record contains the selected column but its value is the empty string.
     */
    public enum EmptyValuePolicy {

        /** Keep the empty string as-is (default). A configured default value never overrides an empty string. */
        KEEP,

        /** Fail with a {@link CSVProjectionException}. */
        ERROR,

        /** Project {@code null}. */
        NULL,

        /** Project a fixed default value. */
        DEFAULT
    }

    /**
     * Policy applied when a record is too short to contain the selected column.
     */
    public enum MissingColumnPolicy {

        /** Fail with a {@link CSVProjectionException} (default). */
        ERROR,

        /** Project {@code null}. */
        NULL,

        /** Project a fixed default value. */
        DEFAULT
    }

    /**
     * Policy applied when the header row does not contain the selected column.
     */
    public enum MissingHeaderPolicy {

        /** Fail compilation with a {@link CSVProjectionException} (default). */
        ERROR,

        /** Project {@code null} for every record. */
        NULL,

        /** Project a fixed default value for every record. */
        DEFAULT
    }

    private static final class ColumnSpec {

        final String header;

        final Integer occurrence;

        final String alias;

        ColumnSpec(final String header, final Integer occurrence, final String alias) {
            this.header = header;
            this.occurrence = occurrence;
            this.alias = alias;
        }
    }

    private static final class CompiledColumn {

        static CompiledColumn at(final int index, final ColumnSpec spec) {
            return new CompiledColumn(index, false, null, spec);
        }

        static CompiledColumn constant(final String value, final ColumnSpec spec) {
            return new CompiledColumn(-1, true, value, spec);
        }

        final int index;

        final boolean constant;

        final String constantValue;

        final ColumnSpec spec;

        private CompiledColumn(final int index, final boolean constant, final String constantValue, final ColumnSpec spec) {
            this.index = index;
            this.constant = constant;
            this.constantValue = constantValue;
            this.spec = spec;
        }
    }

    /**
     * Returns a new projection builder.
     *
     * @return a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<CompiledColumn> columns;

    private final List<String> aliases;

    private final MissingColumnPolicy missingColumnPolicy;

    private final String missingColumnDefault;

    private final EmptyValuePolicy emptyValuePolicy;

    private final String emptyValueDefault;

    private CSVProjection(final List<CompiledColumn> columns, final List<String> aliases, final MissingColumnPolicy missingColumnPolicy,
            final String missingColumnDefault, final EmptyValuePolicy emptyValuePolicy, final String emptyValueDefault) {
        this.columns = columns;
        this.aliases = aliases;
        this.missingColumnPolicy = missingColumnPolicy;
        this.missingColumnDefault = missingColumnDefault;
        this.emptyValuePolicy = emptyValuePolicy;
        this.emptyValueDefault = emptyValueDefault;
    }

    /**
     * Gets the output aliases of this projection, in projection order.
     *
     * @return the output aliases, unmodifiable.
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * Gets the number of projected columns.
     *
     * @return the number of projected columns.
     */
    public int size() {
        return columns.size();
    }

    /**
     * Applies this projection to one record. This method performs no I/O and does not buffer anything beyond the
     * projected values, so it is safe to call while streaming a parser.
     *
     * @param record the record to project.
     * @return the projected record, preserving the source record number and positions.
     * @throws CSVProjectionException if a policy of {@code ERROR} triggers for this record.
     */
    public ProjectedRecord project(final CSVRecord record) {
        final String[] values = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            final CompiledColumn column = columns.get(i);
            if (column.constant) {
                values[i] = column.constantValue;
                continue;
            }
            final ColumnSpec spec = column.spec;
            if (column.index >= record.size()) {
                switch (missingColumnPolicy) {
                case ERROR:
                    throw new CSVProjectionException(record.getRecordNumber(), spec.header, spec.alias, "Record " + record.getRecordNumber()
                            + " has no column '" + spec.header + "' (index " + column.index + ", record size " + record.size() + ")");
                case NULL:
                    values[i] = null;
                    break;
                case DEFAULT:
                    values[i] = missingColumnDefault;
                    break;
                default:
                    throw new IllegalStateException("Unexpected policy " + missingColumnPolicy);
                }
                continue;
            }
            final String value = record.get(column.index);
            if (value != null && value.isEmpty() && emptyValuePolicy != EmptyValuePolicy.KEEP) {
                switch (emptyValuePolicy) {
                case ERROR:
                    throw new CSVProjectionException(record.getRecordNumber(), spec.header, spec.alias, "Record " + record.getRecordNumber()
                            + " has an empty value for column '" + spec.header + "'");
                case NULL:
                    values[i] = null;
                    break;
                case DEFAULT:
                    values[i] = emptyValueDefault;
                    break;
                default:
                    throw new IllegalStateException("Unexpected policy " + emptyValuePolicy);
                }
                continue;
            }
            values[i] = value;
        }
        return new ProjectedRecord(aliases, values, record);
    }
}
