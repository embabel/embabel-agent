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
package com.embabel.agent.skills.support

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InstructionFileReferenceExtractorTest {

    @Test
    fun `extracts markdown link references`() {
        val instructions = "See [the guide](references/guide.md) for details."

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/guide.md"), result)
    }

    @Test
    fun `extracts multiple markdown links`() {
        val instructions = """
            Check [the API docs](references/api.md) and [the setup guide](references/setup.md).
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/api.md", "references/setup.md"), result)
    }

    @Test
    fun `extracts inline resource paths`() {
        val instructions = "Run the extraction script: scripts/extract.py"

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("scripts/extract.py"), result)
    }

    @Test
    fun `extracts paths from all resource directories`() {
        val instructions = """
            Use scripts/build.sh to build.
            Refer to references/docs.md for documentation.
            Images are in assets/logo.png.
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(
            setOf("scripts/build.sh", "references/docs.md", "assets/logo.png"),
            result
        )
    }

    @Test
    fun `ignores http URLs in markdown links`() {
        val instructions = "See [the docs](https://example.com/docs) for more info."

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores mailto links`() {
        val instructions = "Contact [support](mailto:support@example.com)."

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores anchor links`() {
        val instructions = "Jump to [section](#my-section)."

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles null instructions`() {
        val result = InstructionFileReferenceExtractor.extract(null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles blank instructions`() {
        val result = InstructionFileReferenceExtractor.extract("   ")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalizes paths with leading dot-slash`() {
        val instructions = "See [guide](./references/guide.md) for details."

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/guide.md"), result)
    }

    @Test
    fun `extracts nested resource paths`() {
        val instructions = "Use scripts/utils/helper.py to assist."

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("scripts/utils/helper.py"), result)
    }

    @Test
    fun `does not extract paths that do not start with resource directories`() {
        val instructions = "Check out my-folder/file.txt or other/path.md"

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts both markdown links and inline paths`() {
        val instructions = """
            See [the docs](references/api.md) for API reference.

            Run scripts/build.sh to compile.
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/api.md", "scripts/build.sh"), result)
    }

    @Test
    fun `deduplicates references`() {
        val instructions = """
            Run scripts/build.sh first.
            Then run scripts/build.sh again.
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("scripts/build.sh"), result)
    }
}
