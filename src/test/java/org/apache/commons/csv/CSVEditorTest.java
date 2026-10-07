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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVEditor}.
 */
class CSVEditorTest {

    private static final String CR_LF = "\r\n";

    private static List<String[]> parse(final String text, final CSVFormat format) {
        final List<String[]> records = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(text, format)) {
            for (final CSVRecord record : parser) {
                final String[] values = new String[record.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = record.get(i);
                }
                records.add(values);
            }
            return records;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertReparsesTo(final String expected, final String edited, final CSVFormat format) {
        final List<String[]> expectedRecords = parse(expected, format);
        final List<String[]> actualRecords = parse(edited, format);
        assertEquals(expectedRecords.size(), actualRecords.size(), "record count");
        for (int r = 0; r < expectedRecords.size(); r++) {
            assertArrayEquals(expectedRecords.get(r), actualRecords.get(r), "record " + r);
        }
    }

    @Test
    void testCellEditCoordinates() {
        final CSVEditor.CellEdit edit = CSVEditor.cell(2, 1, "v");
        assertEquals(2, edit.getRecord());
        assertEquals(1, edit.getColumn());
        assertEquals("v", edit.getValue());
    }

    @Test
    void testCellEditNegativeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> CSVEditor.cell(-1, 0, "v"));
        assertThrows(IllegalArgumentException.class, () -> CSVEditor.cell(0, -1, "v"));
    }

    @Test
    void testCellEditNullValueBecomesEmpty() {
        assertEquals("", CSVEditor.cell(0, 0, null).getValue());
    }

    @Test
    void testCommentCrlfMultilineAndEscapedQuotesPreserved() throws CSVException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();
        final String input =
                "# a comment" + CR_LF +
                "a,b,c" + CR_LF +
                "\"line 1" + CR_LF + "line 2\",\"she said \"\"hi\"\"\",tail" + CR_LF +
                "d,e,f" + CR_LF;
        final String expected =
                "# a comment" + CR_LF +
                "a,b," + CR_LF +
                "\"line 1" + CR_LF + "line 2\",\"she said \"\"hi\"\"\",EDITED" + CR_LF +
                "d,e,f" + CR_LF;
        final String actual = CSVEditor.edit(input, format,
                CSVEditor.cell(0, 2, ""),
                CSVEditor.cell(1, 2, "EDITED"));
        assertEquals(expected, actual);

        // Every unedited region is byte-identical to the original. Offsets after the first replacement shift by
        // the length difference, so locate every region independently in both texts instead.
        assertEquals(input.substring(0, input.indexOf('a')), actual.substring(0, actual.indexOf('a')),
                "comment + CRLF");
        final String multiline = "\"line 1" + CR_LF + "line 2\"";
        assertEquals(multiline, input.substring(input.indexOf(multiline), input.indexOf(multiline) + multiline.length()),
                "multiline field in input");
        assertEquals(multiline, actual.substring(actual.indexOf(multiline), actual.indexOf(multiline) + multiline.length()),
                "multiline field in actual");
        final String tail = "d,e,f" + CR_LF;
        assertEquals(tail, input.substring(input.length() - tail.length()), "tail in input");
        assertEquals(tail, actual.substring(actual.length() - tail.length()), "tail in actual");
        // The untouched escaped-quote field that was not edited remains byte-identical, including its original quotes.
        final String quotedEscaped = "\"she said \"\"hi\"\"\"";
        assertEquals(quotedEscaped, actual.substring(actual.indexOf(quotedEscaped),
                actual.indexOf(quotedEscaped) + quotedEscaped.length()), "escaped quotes field");

        assertReparsesTo(expected, actual, format);
    }

    @Test
    void testEmptyValue() throws CSVException {
        assertEquals(",bar\n", CSVEditor.edit("foo,bar\n", CSVFormat.DEFAULT, CSVEditor.cell(0, 0, "")));
        assertEquals("foo,\n", CSVEditor.edit("foo,bar\n", CSVFormat.DEFAULT, CSVEditor.cell(0, 1, "")));
    }

    @Test
    void testEmptyValueInQuotedField() throws CSVException {
        final String actual = CSVEditor.edit("\"x\",\"y\"\n", CSVFormat.DEFAULT, CSVEditor.cell(0, 0, ""));
        assertEquals(",\"y\"\n", actual);
        assertReparsesTo(",\"y\"\n", actual, CSVFormat.DEFAULT);
    }

    @Test
    void testEscapeCharacterFormat() throws CSVException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setQuote(null).setEscape('\\').build();
        final String actual = CSVEditor.edit("a,b\n", format, CSVEditor.cell(0, 0, "x,y"));
        assertEquals("x\\,y,b\n", actual);
        final List<String[]> reparsed = parse(actual, format);
        assertArrayEquals(new String[] { "x,y", "b" }, reparsed.get(0));
    }

    @Test
    void testEscapeCharacterInValueIsDoubled() throws CSVException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setQuote(null).setEscape('\\').get();
        final String actual = CSVEditor.edit("a,b\n", format, CSVEditor.cell(0, 0, "x\\y"));
        assertEquals("x\\\\y,b\n", actual);
        assertReparsesTo("x\\y,b\n", actual, format);
    }

    @Test
    void testIterableOverload() throws CSVException {
        final List<CSVEditor.CellEdit> edits = Arrays.asList(CSVEditor.cell(0, 1, "z"));
        assertEquals("a,z\n", CSVEditor.edit("a,b\n", CSVFormat.DEFAULT, edits));
    }

    @Test
    void testLineFeedsOnlyStylePreserved() throws CSVException {
        final String input = "a,b\nc,d\ne,f\n";
        final String actual = CSVEditor.edit(input, CSVFormat.DEFAULT, CSVEditor.cell(1, 0, "X"));
        assertEquals("a,b\nX,d\ne,f\n", actual);
    }

    @Test
    void testNoEditsReturnsSameInstance() throws CSVException {
        final String input = "a,b\nc,d\n";
        assertSame(input, CSVEditor.edit(input, CSVFormat.DEFAULT));
    }

    @Test
    void testNoQuoteAndNoEscapeFormat() throws CSVException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setQuote(null).build();
        // Plain values can still be replaced.
        assertEquals("plain,b\n", CSVEditor.edit("a,b\n", format, CSVEditor.cell(0, 0, "plain")));
        // A value that requires quoting cannot be encoded.
        assertThrows(CSVException.class,
                () -> CSVEditor.edit("a,b\n", format, CSVEditor.cell(0, 0, "x,y")));
    }

    @Test
    void testNoTrailingNewline() throws CSVException {
        final String input = "a,b\nc,d";
        final String actual = CSVEditor.edit(input, CSVFormat.DEFAULT, CSVEditor.cell(1, 1, "Q"));
        assertEquals("a,b\nc,Q", actual);
        assertReparsesTo("a,b\nc,Q\n", actual, CSVFormat.DEFAULT);
    }

    @Test
    void testOutOfBoundsColumn() {
        final CSVException e = assertThrows(CSVException.class,
                () -> CSVEditor.edit("a,b\n", CSVFormat.DEFAULT, CSVEditor.cell(0, 9, "x")));
        assertEquals(String.format("Column %,d does not exist in record %,d, which has %,d column(s)", 9, 0, 2),
                e.getMessage());
    }

    @Test
    void testOutOfBoundsRecord() {
        assertThrows(CSVException.class,
                () -> CSVEditor.edit("a,b\n", CSVFormat.DEFAULT, CSVEditor.cell(5, 0, "x")));
    }

    @Test
    void testQuotedFieldWithDelimiterNewLineAndQuote() throws CSVException {
        final String actual = CSVEditor.edit("foo,bar\n", CSVFormat.DEFAULT,
                CSVEditor.cell(0, 0, "x,y"),
                CSVEditor.cell(0, 1, "l1\nl2\"q"));
        assertEquals(Arrays.asList("x,y", "l1\nl2\"q"), Arrays.asList(parse(actual, CSVFormat.DEFAULT).get(0)));
    }

    @Test
    void testRepeatedModificationLastWins() throws CSVException {
        final String actual = CSVEditor.edit("a,b\n", CSVFormat.DEFAULT,
                CSVEditor.cell(0, 0, "first"),
                CSVEditor.cell(0, 0, "second"));
        assertEquals("second,b\n", actual);
    }

    @Test
    void testReplaceInsideMultilineQuotedField() throws CSVException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "\"a\r\nb\",c\r\n";
        final String actual = CSVEditor.edit(input, format, CSVEditor.cell(0, 0, "X"));
        assertEquals("X,c\r\n", actual);
        assertReparsesTo("X,c\r\n", actual, format);
    }

    @Test
    void testTrailingDelimiterFormat() throws CSVException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setTrailingDelimiter(true).get();
        // The trailing delimiter after the empty value does not add a column.
        final String input = "a,b,\n";
        final String actual = CSVEditor.edit(input, format, CSVEditor.cell(0, 0, "X"));
        assertEquals("X,b,\n", actual);
        assertReparsesTo("X,b\n", actual, format);
        assertThrows(CSVException.class,
                () -> CSVEditor.edit(input, format, CSVEditor.cell(0, 2, "Y")));
    }
}
