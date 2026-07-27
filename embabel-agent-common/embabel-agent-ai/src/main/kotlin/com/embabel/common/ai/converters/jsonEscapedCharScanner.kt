/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.common.ai.converters

/**
 * Fix malformed JSON where the LLM has incorrectly escaped quote characters
 * that should be JSON string delimiters.
 *
 * This fixes cases like `"span": \"Glazunov's violin concerto\",` where the
 * LLM escapes the quotes that delimit the string value itself. Jackson's
 * lenient features can't handle this because the backslash before the opening
 * quote makes it syntactically invalid.
 *
 * The input is scanned tracking string boundaries, so `\"` is rewritten to `"`
 * only where a string delimiter can occur, per adjacent structural character:
 *
 * - after `:` (value position): `{"name": \"test\"}` -> `{"name": "test"}`
 * - after `{` (property name): `{\"name\": "x"}` -> `{"name": "x"}`
 * - after `[` or `,` (array element): `[\"a\", \"b\"]` -> `["a", "b"]`
 * - inside a string opened by `\"`, a `\"` followed by `:`, `,`, `}`, `]` or
 *   end of input closes the string; any other `\"` stays an escaped quote:
 *   `{"text": \"say \"hi\" now\"}` -> `{"text": "say \"hi\" now"}`
 *
 * A string opened by a plain `"` is copied verbatim, escapes included, so
 * valid JSON always comes back unchanged. Examples that are NOT altered:
 *
 * - `{"title": "Hello \"World\", \"How\" are you?"}` (issue #1804)
 * - `{"name": "flowchart LR\n  a[\"A\"]\n  b[\"B\"]"}` (mermaid, issue #1788)
 *
 * Single pass over the input, no regular expressions; only invoked on the
 * fallback path of [JacksonOutputConverter.convert] after a failed parse.
 */
internal fun fixMalformedEscapedQuotes(json: String): String {
    val repaired = StringBuilder(json.length)
    // Scanner state: outside any string, inside a string opened by a plain `"`,
    // or inside a string opened by a malformed `\"` (openedByEscapedQuote = true).
    var inString = false
    var openedByEscapedQuote = false
    // True when a string may start at the current position, i.e. at the start of
    // input or after `{`, `[`, `:` or `,`.
    var atDelimiterPosition = true
    var i = 0
    while (i < json.length) {
        val c = json[i]
        val escapedQuote = c == '\\' && i + 1 < json.length && json[i + 1] == '"'
        when {
            // A plain quote opens a string normally.
            !inString && c == '"' -> {
                inString = true
                openedByEscapedQuote = false
                repaired.append(c)
            }
            // `\"` where a string may start: the LLM escaped the opening
            // delimiter. Drop the backslash and remember how the string opened.
            !inString && escapedQuote && atDelimiterPosition -> {
                inString = true
                openedByEscapedQuote = true
                repaired.append('"')
                i++
            }
            // Any other character between strings: copy it and track whether
            // the next position is one where a string may start.
            !inString -> {
                if (!c.isWhitespace()) {
                    atDelimiterPosition = c in "{[:,"
                }
                repaired.append(c)
            }
            // From here on we are inside a string. A plain quote closes it.
            c == '"' -> {
                inString = false
                atDelimiterPosition = false
                repaired.append(c)
            }
            // `\"` in a string opened by `\"`, followed by a structural character
            // or end of input: the matching malformed closing delimiter. Drop the
            // backslash. In a string opened by a plain quote this never fires, so
            // legitimate escaped quotes in valid JSON are preserved.
            escapedQuote && openedByEscapedQuote && closesString(json, i + 2) -> {
                inString = false
                atDelimiterPosition = false
                repaired.append('"')
                i++
            }
            // Any other escape pair inside a string is copied as is.
            c == '\\' && i + 1 < json.length -> {
                repaired.append(c).append(json[i + 1])
                i++
            }
            else -> repaired.append(c)
        }
        i++
    }
    return repaired.toString()
}

/**
 * True if the first non-whitespace character at or after [index] is one that
 * can follow a closing string delimiter, or if the input ends first.
 */
private fun closesString(json: String, index: Int): Boolean {
    var i = index
    while (i < json.length && json[i].isWhitespace()) {
        i++
    }
    return i == json.length || json[i] in ":,}]"
}
