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

    // ─── Fenced code blocks must not contribute file references ──────────
    //
    // Skill bodies routinely include code examples in ``` fences. Anything
    // inside is a sample, not a reference — it must NOT be validated as a
    // local file. Otherwise any skill teaching code (most of them) is
    // forced to avoid `[label](path)`-shaped lines and `scripts/foo.x`
    // strings inside its examples, which is a footgun.

    @Test
    fun `ignores markdown link inside fenced code block`() {
        val instructions = """
            Real reference: [the guide](references/guide.md).

            Example code:

            ```javascript
            const r = await fetch(url);
            console.log(`- [${'$'}{hit.title}](${'$'}{hit.url})`);
            ```
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        // Only the prose link counts — the JS template literal inside the
        // fence must be left alone.
        assertEquals(setOf("references/guide.md"), result)
    }

    @Test
    fun `ignores resource path inside fenced code block`() {
        val instructions = """
            Run scripts/build.sh to compile.

            ```python
            # Don't do this — it's just an illustration
            subprocess.run(["python", "scripts/legacy.py"])
            ```
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        // The prose mention is a real reference; the in-fence string is not.
        assertEquals(setOf("scripts/build.sh"), result)
    }

    @Test
    fun `ignores tilde-fenced code block`() {
        val instructions = """
            See [docs](references/docs.md).

            ~~~
            scripts/oops.sh
            [link](references/oops.md)
            ~~~
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/docs.md"), result)
    }

    @Test
    fun `ignores fence with language tag`() {
        val instructions = """
            ```kotlin
            // [Foo](references/foo.kt)
            val x = "scripts/x.kt"
            ```
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles unclosed fenced code block as code through end of input`() {
        // CommonMark implicitly closes a fence at end of document. The
        // extractor must follow the same rule — otherwise a malformed
        // skill body would suddenly start treating its code as prose.
        val instructions = """
            Intro paragraph mentions [real](references/real.md).

            ```
            scripts/never-real.py
            [also](references/never-real.md)
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/real.md"), result)
    }

    @Test
    fun `handles multiple fenced blocks interleaved with prose`() {
        val instructions = """
            First, see [setup](references/setup.md).

            ```
            scripts/in-fence-1.sh
            ```

            Then run scripts/build.sh.

            ```bash
            scripts/in-fence-2.sh
            ```

            Finally consult assets/diagram.png.
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(
            setOf("references/setup.md", "scripts/build.sh", "assets/diagram.png"),
            result,
        )
    }

    @Test
    fun `extracts references on the same line as a closing fence terminator`() {
        // Defensive: prose immediately following the closing fence on the
        // next line must still be scanned. Verifies the fence regex doesn't
        // eat the trailing newline + following content.
        val instructions = """
            ```
            scripts/in-fence.sh
            ```
            See [the docs](references/docs.md) for details.
        """.trimIndent()

        val result = InstructionFileReferenceExtractor.extract(instructions)

        assertEquals(setOf("references/docs.md"), result)
    }
}
