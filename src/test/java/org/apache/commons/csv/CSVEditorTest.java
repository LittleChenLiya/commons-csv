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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CSVEditor}.
 */
class CSVEditorTest {

    /**
     * Asserts that parsing {@code edited} yields the same values as parsing {@code original}, except for the cells
     * listed in {@code expectedValues} as {@code recordIndex, columnIndex, newValue} triples.
     */
    private void assertValuesExcept(final CSVFormat format, final String original, final String edited,
            final Object... expectedValues) throws IOException {
        final List<CSVRecord> before = CSVParser.parse(original, format).getRecords();
        final List<CSVRecord> after = CSVParser.parse(edited, format).getRecords();
        assertEquals(before.size(), after.size(), "record count changed");
        for (int r = 0; r < before.size(); r++) {
            assertEquals(before.get(r).size(), after.get(r).size(), "column count changed in record " + r);
            for (int c = 0; c < before.get(r).size(); c++) {
                Object expected = before.get(r).get(c);
                for (int i = 0; i < expectedValues.length; i += 3) {
                    if (((Number) expectedValues[i]).intValue() == r && ((Number) expectedValues[i + 1]).intValue() == c) {
                        expected = expectedValues[i + 2];
                    }
                }
                assertEquals(expected, after.get(r).get(c), "value at record " + r + ", column " + c);
            }
        }
    }

    @Test
    void testPreservesCommentsCrlfMultilineAndQuotes() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();
        final String input = "# a comment\r\n" +
                "a,b,c\r\n" +
                "\r\n" +
                "# another comment\r\n" +
                "1,\"two\r\nlines\",\"he said \"\"hi\"\"\"\r\n" +
                "x,y,z\r\n";
        final String edited = CSVEditor.of(format).set(0, 1, "B").set(1, 2, "done").apply(input);
        final String expected = "# a comment\r\n" +
                "a,B,c\r\n" +
                "\r\n" +
                "# another comment\r\n" +
                "1,\"two\r\nlines\",done\r\n" +
                "x,y,z\r\n";
        assertEquals(expected, edited);
        assertValuesExcept(format, input, edited, 0, 1, "B", 1, 2, "done");
    }

    @Test
    void testNullValueWithNullString() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setNullString("NULL").get();
        final String input = "a,b\nc,d\n";
        final String edited = CSVEditor.of(format).set(1, 0, null).apply(input);
        assertEquals("a,b\nNULL,d\n", edited);
        assertNull(CSVParser.parse(edited, format).getRecords().get(1).get(0));
    }

    @Test
    void testNullValueWithoutNullString() throws IOException {
        final String input = "a,b\nc,d\n";
        final String edited = CSVEditor.of(CSVFormat.DEFAULT).set(0, 1, null).apply(input);
        assertEquals("a,\"\"\nc,d\n", edited);
        assertEquals("", CSVParser.parse(edited, CSVFormat.DEFAULT).getRecords().get(0).get(1));
    }

    @Test
    void testEmptyReplacementValue() throws IOException {
        final String input = "a,b,c\n";
        final String edited = CSVEditor.of(CSVFormat.DEFAULT).set(0, 1, "").apply(input);
        assertEquals("a,\"\",c\n", edited);
        assertEquals("", CSVParser.parse(edited, CSVFormat.DEFAULT).getRecords().get(0).get(1));
    }

    @Test
    void testValueWithDelimiter() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,b,c\n1,2,3\n";
        final String edited = CSVEditor.of(format).set(1, 1, "x,y").apply(input);
        assertEquals("a,b,c\n1,\"x,y\",3\n", edited);
        assertValuesExcept(format, input, edited, 1, 1, "x,y");
    }

    @Test
    void testValueWithQuotesAndLineBreaks() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,b\nc,d\n";
        final String value = "he said \"hi\"\r\nbye";
        final String edited = CSVEditor.of(format).set(1, 0, value).apply(input);
        assertEquals("a,b\n\"he said \"\"hi\"\"\r\nbye\",d\n", edited);
        assertValuesExcept(format, input, edited, 1, 0, value);
    }

    @Test
    void testOutOfBoundsCoordinates() {
        final String input = "a,b\nc,d\n";
        final CSVEditor editor = CSVEditor.of(CSVFormat.DEFAULT);
        assertThrows(IndexOutOfBoundsException.class, () -> CSVEditor.of(CSVFormat.DEFAULT).set(2, 0, "x").apply(input));
        assertThrows(IndexOutOfBoundsException.class, () -> CSVEditor.of(CSVFormat.DEFAULT).set(0, 2, "x").apply(input));
        assertThrows(IndexOutOfBoundsException.class, () -> CSVEditor.of(CSVFormat.DEFAULT).set(99, 99, "x").apply(input));
        assertThrows(IndexOutOfBoundsException.class, () -> editor.set(-1, 0, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> editor.set(0, -1, "x"));
    }

    @Test
    void testDuplicateEditLastWins() throws IOException {
        final String input = "a,b\nc,d\n";
        final String edited = CSVEditor.of(CSVFormat.DEFAULT).set(0, 0, "first").set(0, 0, "second").apply(input);
        assertEquals("second,b\nc,d\n", edited);
    }

    @Test
    void testNoQuoteNoEscapeFormat() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setQuote(null).get();
        final String input = "a,b\nc,d\n";
        final String edited = CSVEditor.of(format).set(0, 1, "hello world").set(1, 0, "see").apply(input);
        assertEquals("a,hello world\nsee,d\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "hello world", 1, 0, "see");
    }

    @Test
    void testNoTrailingNewline() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,b\nc,d";
        final String edited = CSVEditor.of(format).set(1, 1, "D").apply(input);
        assertEquals("a,b\nc,D", edited);
        assertFalse(edited.endsWith("\n"));
        assertValuesExcept(format, input, edited, 1, 1, "D");
    }

    @Test
    void testNoEditsReturnsInput() throws IOException {
        final String input = "a,b\nc,d\n";
        assertEquals(input, CSVEditor.of(CSVFormat.DEFAULT).apply(input));
    }

    @Test
    void testEmptyField() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,,c\n";
        final String edited = CSVEditor.of(format).set(0, 1, "b").apply(input);
        assertEquals("a,b,c\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "b");
    }

    @Test
    void testLastFieldAtEofWithoutNewline() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,b";
        final String edited = CSVEditor.of(format).set(0, 1, "B").apply(input);
        assertEquals("a,B", edited);
        assertValuesExcept(format, input, edited, 0, 1, "B");
    }

    @Test
    void testQuotedFieldReplaced() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "\"a\",\"b\",\"c\"\n";
        final String edited = CSVEditor.of(format).set(0, 1, "x").apply(input);
        assertEquals("\"a\",x,\"c\"\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "x");
    }

    @Test
    void testHeaderRecordIsNotCounted() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().get();
        final String input = "h1,h2\na,b\n";
        final String edited = CSVEditor.of(format).set(0, 0, "A").apply(input);
        assertEquals("h1,h2\nA,b\n", edited);
        assertValuesExcept(format, input, edited, 0, 0, "A");
    }

    @Test
    void testSkipHeaderRecordIsNotCounted() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setHeader("h1", "h2").setSkipHeaderRecord(true).get();
        final String input = "h1,h2\na,b\n";
        final String edited = CSVEditor.of(format).set(0, 1, "B").apply(input);
        assertEquals("h1,h2\na,B\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "B");
    }

    @Test
    void testEditsAppliedOutOfOrder() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,b,c\n1,2,3\n";
        final String edited = CSVEditor.of(format).set(1, 2, "three").set(0, 0, "A").set(1, 0, "one").apply(input);
        assertEquals("A,b,c\none,2,three\n", edited);
        assertValuesExcept(format, input, edited, 0, 0, "A", 1, 0, "one", 1, 2, "three");
    }

    @Test
    void testMultiLineQuotedFieldReplaced() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,\"line1\nline2\",c\nd,e,f\n";
        final String edited = CSVEditor.of(format).set(0, 1, "new").apply(input);
        assertEquals("a,new,c\nd,e,f\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "new");
    }

    @Test
    void testCrOnlyLineEndings() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT;
        final String input = "a,b\rc,d\r";
        final String edited = CSVEditor.of(format).set(1, 0, "C").apply(input);
        assertEquals("a,b\rC,d\r", edited);
        assertValuesExcept(format, input, edited, 1, 0, "C");
    }

    @Test
    void testSemicolonDelimiterAndEscape() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').setEscape('\\').get();
        final String input = "a;b\\;c\nd;e\n";
        final String edited = CSVEditor.of(format).set(0, 1, "x;y").apply(input);
        assertEquals("a;\"x;y\"\nd;e\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "x;y");
    }

    @Test
    void testIgnoreSurroundingSpaces() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setIgnoreSurroundingSpaces(true).get();
        final String input = "a , b \nc,d\n";
        final String edited = CSVEditor.of(format).set(0, 1, "X").apply(input);
        assertEquals("a ,X\nc,d\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "X");
    }

    @Test
    void testMultiCharDelimiter() throws IOException {
        final CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter("[|]").get();
        final String input = "a[|]b[|]c\n1[|][|]3\n";
        final String edited = CSVEditor.of(format).set(0, 1, "B").set(1, 1, "two").apply(input);
        assertEquals("a[|]B[|]c\n1[|]two[|]3\n", edited);
        assertValuesExcept(format, input, edited, 0, 1, "B", 1, 1, "two");
    }
}
