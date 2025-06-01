/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.shell

// Thanks to Guillaume Laforge: https://glaforge.dev/posts/2025/02/27/pretty-print-markdown-on-the-console/
fun markdownToConsole(md: String): String {
    return md
        // Bold
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "\u001B[1m$1\u001B[0m")
        // Italic
        .replace(Regex("\\*(.*?)\\*"), "\u001B[3m$1\u001B[0m")
        // Underline
        .replace(Regex("__(.*?)__"), "\u001B[4m$1\u001B[0m")
        // Strikethrough
        .replace(Regex("~~(.*?)~~"), "\u001B[9m$1\u001B[0m")
        // Blockquote
        .replace(
            Regex("(> ?.*)"),
            "\u001B[3m\u001B[34m\u001B[1m$1\u001B[22m\u001B[0m"
        )
        // Lists (bold magenta number and bullet)
        .replace(
            Regex("([\\d]+\\.|-|\\*) (.*)"),
            "\u001B[35m\u001B[1m$1\u001B[22m\u001B[0m $2"
        )
        // Block code (black on gray)
        .replace(
            Regex("(?s)```(\\w+)?\\n(.*?)\\n```"),
            "\u001B[3m\u001B[1m$1\u001B[22m\u001B[0m\n\u001B[57;107m$2\u001B[0m\n"
        )
        // Inline code (black on gray)
        .replace(Regex("`(.*?)`"), "\u001B[57;107m$1\u001B[0m")
        // Headers (cyan bold)
        .replace(
            Regex("(#{1,6}) (.*?)\\n"),
            "\u001B[36m\u001B[1m$1 $2\u001B[22m\u001B[0m\n"
        )
        // Headers with a single line of text followed by 2 or more equal signs
        .replace(
            Regex("(.*?\\n={2,}\\n)"),
            "\u001B[36m\u001B[1m$1\u001B[22m\u001B[0m\n"
        )
        // Headers with a single line of text followed by 2 or more dashes
        .replace(
            Regex("(.*?\\n-{2,}\\n)"),
            "\u001B[36m\u001B[1m$1\u001B[22m\u001B[0m\n"
        )
        // Images (blue underlined)
        .replace(
            Regex("!\\[(.*?)]\\((.*?)\\)"),
            "\u001B[34m$1\u001B[0m (\u001B[34m\u001B[4m$2\u001B[0m)"
        )
        // Links (blue underlined)
        .replace(
            Regex("!?\\[(.*?)]\\((.*?)\\)"),
            "\u001B[34m$1\u001B[0m (\u001B[34m\u001B[4m$2\u001B[0m)"
        )
}
