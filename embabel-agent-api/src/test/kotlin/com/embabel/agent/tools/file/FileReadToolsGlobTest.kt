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
package com.embabel.agent.tools.file

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Comprehensive glob-semantics spec for [FileReadTools.findFiles], serving as a regression net for the
 * ripgrep/gitignore matching behaviour.
 *
 * Every test asserts the DESIRED (ripgrep/gitignore) semantics:
 * - a double-star crosses directory boundaries and, when followed by a slash, matches ZERO or more
 *   directories (so prefixed and leading double-star patterns include files at the top level);
 * - a single star / question mark stays within one path segment;
 * - brace alternation and character classes keep working (must not regress);
 * - findFiles returns regular files only, never directories.
 */
class FileReadToolsGlobTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fileReadTools: FileReadTools
    private lateinit var rootPath: String

    /** Every regular file created in the fixture (forward-slash, relative to the working dir). */
    private val allFiles = setOf(
        "top.sol",
        "R.sol",
        "src/A.sol",
        "src/B.sol",
        "src/Foo.sol",
        "src/sub/C.sol",
        "src/sub/deep/D.sol",
        "test/T.t.sol",
        "README.md",
        "docs/guide.md",
    )

    /** Every .sol file, at any depth, root included. */
    private val allSol = allFiles.filter { it.endsWith(".sol") }.toSet()

    @BeforeEach
    fun setUp() {
        rootPath = tempDir.toString()
        fileReadTools = FileTools.readOnly(rootPath, emptyList())
        allFiles.forEach { rel ->
            val p = tempDir.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, "// $rel")
        }
        // An empty directory, to assert directories are never returned as matches.
        Files.createDirectories(tempDir.resolve("emptydir"))
    }

    /** Absolute results → forward-slash paths relative to the working dir, for stable assertions. */
    private fun find(glob: String, findHighest: Boolean = false): Set<String> {
        val base = Paths.get(rootPath).toAbsolutePath().normalize()
        return fileReadTools.findFiles(glob, findHighest)
            .map { base.relativize(Paths.get(it)).toString().replace('\\', '/') }
            .toSet()
    }

    @Nested
    inner class ZeroOrMoreDirectories {

        @Test
        fun `prefixed double-star slash matches direct children and nested`() {
            assertEquals(
                setOf("src/A.sol", "src/B.sol", "src/Foo.sol", "src/sub/C.sol", "src/sub/deep/D.sol"),
                find("src/**/*.sol"),
            )
        }

        @Test
        fun `leading double-star slash matches the root level too`() {
            assertEquals(allSol, find("**/*.sol"))
        }

        @Test
        fun `leading double-star slash for md matches root readme`() {
            assertEquals(setOf("README.md", "docs/guide.md"), find("**/*.md"))
        }

        @Test
        fun `middle double-star slash matches zero directories`() {
            // 'src/**/Foo.sol' must match src/Foo.sol (zero dirs between src and Foo).
            assertEquals(setOf("src/Foo.sol"), find("src/**/Foo.sol"))
        }

        @Test
        fun `middle double-star slash matches across directories`() {
            // A '**/' between segments also matches one-or-more dirs; guard this stays working.
            assertEquals(setOf("src/sub/C.sol"), find("src/**/C.sol"))
        }
    }

    @Nested
    inner class SingleSegmentWildcards {

        @Test
        fun `single star matches only the root level`() {
            assertEquals(setOf("top.sol", "R.sol"), find("*.sol"))
        }

        @Test
        fun `single star under a directory matches only its direct children, not nested`() {
            // src/*.sol matches the direct children but must NOT reach src/sub/C.sol.
            assertEquals(setOf("src/A.sol", "src/B.sol", "src/Foo.sol"), find("src/*.sol"))
        }

        @Test
        fun `question mark matches exactly one character within a segment`() {
            assertEquals(setOf("src/A.sol", "src/B.sol"), find("src/?.sol")) // not Foo.sol
            assertEquals(setOf("R.sol"), find("?.sol")) // not top.sol
        }

        @Test
        fun `star in a specific directory`() {
            assertEquals(setOf("test/T.t.sol"), find("test/*.sol"))
        }
    }

    @Nested
    inner class GlobCharacterFeatures {

        @Test
        fun `brace alternation must keep working`() {
            assertEquals(setOf("src/A.sol", "src/B.sol"), find("src/{A,B}.sol"))
        }

        @Test
        fun `character class must keep working`() {
            assertEquals(setOf("src/A.sol", "src/B.sol"), find("src/[AB].sol"))
        }

        @Test
        fun `negated character class must keep working`() {
            assertEquals(setOf("src/B.sol"), find("src/[!A].sol")) // single char, not 'A'; excludes Foo
        }

        @Test
        fun `character range must keep working`() {
            // [A-C] matches a single char in A..C: A.sol and B.sol (no C.sol at src root), not Foo.sol.
            assertEquals(setOf("src/A.sol", "src/B.sol"), find("src/[A-C].sol"))
        }

        @Test
        fun `character class matches a single char and must not cross a directory boundary`() {
            // A class '[...]' stands for ONE char within a segment; like the JDK glob it must never
            // match the path separator. 'foo[!x]bar' must match 'fooZbar' but NOT 'foo/bar'.
            Files.writeString(tempDir.resolve("fooZbar"), "// same-segment match")
            Files.createDirectories(tempDir.resolve("foo"))
            Files.writeString(tempDir.resolve("foo/bar"), "// separator must not be swallowed")
            assertEquals(setOf("fooZbar"), find("foo[!x]bar"))
        }

        @Test
        fun `a literal bracket inside a class must match a bracket file name`() {
            // '[[]' is a class containing a literal '['; like the JDK glob it must match a file named
            // '[' rather than throwing. NIO's getPathMatcher accepts it, so we must too.
            Files.writeString(tempDir.resolve("["), "// literal bracket")
            assertEquals(setOf("["), find("[[]"))
        }
    }

    @Nested
    inner class LiteralsAndEscaping {

        @Test
        fun `a literal pattern with no wildcards matches exactly that one file`() {
            assertEquals(setOf("top.sol"), find("top.sol"))
            assertEquals(setOf("src/A.sol"), find("src/A.sol"))
        }

        @Test
        fun `dot is matched literally, not as a regex any-char`() {
            // Trap: if '.' were not escaped, the glob regex '[^/]*.md' would also grab this file.
            Files.writeString(tempDir.resolve("READMEXmd"), "// trap")
            assertEquals(setOf("README.md"), find("*.md"))
        }

        @Test
        fun `a backslash escapes a glob metacharacter to match it literally`() {
            // '{' is legal in filenames on every platform (unlike '*'/'?'); '\{' must match a literal
            // brace, not open a brace group.
            Files.writeString(tempDir.resolve("a{b.sol"), "// literal brace")
            assertEquals(setOf("a{b.sol"), find("a\\{b.sol"))
        }
    }

    @Nested
    inner class DoubleStarCrossing {

        @Test
        fun `bare double-star returns every file at any depth`() {
            assertEquals(allFiles, find("**"))
        }

        @Test
        fun `directory prefix double-star returns every file under it`() {
            assertEquals(
                setOf("src/A.sol", "src/B.sol", "src/Foo.sol", "src/sub/C.sol", "src/sub/deep/D.sol"),
                find("src/**"),
            )
        }

        @Test
        fun `double-star without a slash crosses directories including root`() {
            assertEquals(allSol, find("**.sol"))
        }
    }

    @Nested
    inner class FilesOnlyNotDirectories {

        @Test
        fun `a glob whose literal target is a directory returns nothing`() {
            // Even when the pattern names a directory outright, findFiles yields regular files only.
            assertEquals(emptySet<String>(), find("src"))
            assertEquals(emptySet<String>(), find("src/sub"))
            assertEquals(emptySet<String>(), find("emptydir"))
        }

        @Test
        fun `a wildcard never matches a directory entry`() {
            // '*' at the root would match the 'src'/'test'/'docs'/'emptydir' segment names, but they are
            // directories, so only the root-level regular files come back.
            assertEquals(setOf("top.sol", "R.sol", "README.md"), find("*"))
        }
    }

    @Nested
    inner class OverMatchGuards {

        // A '**/' segment matches zero-or-more whole directories; it must NOT glue onto an adjacent name.
        // 'src/**/Foo.sol' must match only paths whose last segment is exactly Foo.sol, never src/BarFoo.sol.
        // Extra files are created locally so the shared exact-set tests keep holding.
        @Test
        fun `zero-dir match does not over-match a similar file name`() {
            Files.writeString(tempDir.resolve("src/BarFoo.sol"), "// look-alike")
            // Only the file whose last segment IS exactly Foo.sol; BarFoo.sol must be excluded.
            assertEquals(setOf("src/Foo.sol"), find("src/**/Foo.sol"))
        }

        @Test
        fun `zero-dir match does not over-match a sibling directory with the same prefix`() {
            Files.createDirectories(tempDir.resolve("srcTest"))
            Files.writeString(tempDir.resolve("srcTest/A.sol"), "// sibling dir")
            // 'src' is a whole segment; the neighbouring 'srcTest/' dir must not leak in.
            assertEquals(
                setOf("src/A.sol", "src/B.sol", "src/Foo.sol", "src/sub/C.sol", "src/sub/deep/D.sol"),
                find("src/**/*.sol"),
            )
        }
    }

    /**
     * Edge cases that reference implementations (git :(glob), minimatch, micromatch, bash globstar)
     * cover. Each asserts the DESIRED ripgrep/gitignore semantics; they exercise the harder corners of
     * the single-regex double-star-slash translation.
     */
    @Nested
    inner class ReferenceEdgeCases {

        @Test
        fun `two double-star-slash segments each match zero or more dirs independently`() {
            // Fixture: a literal 'a' .. 'b' .. 'c' skeleton with 0/1 dirs in each gap (all four combos).
            listOf("a/b/c", "a/x/b/c", "a/b/y/c", "a/x/b/y/c").forEach {
                val p = tempDir.resolve(it)
                Files.createDirectories(p.parent)
                Files.writeString(p, "// $it")
            }
            // Every combination matches: each '**/' resolves zero-or-more dirs independently, including
            // the mixed cases (a/x/b/c and a/b/y/c).
            assertEquals(
                setOf("a/b/c", "a/x/b/c", "a/b/y/c", "a/x/b/y/c"),
                find("a/**/b/**/c"),
            )
        }

        @Test
        fun `adjacent double-star-slash segments collapse to one`() {
            // 'src/**/**/*.sol' must behave exactly like 'src/**/*.sol' (all .sol under src, any depth).
            assertEquals(
                setOf("src/A.sol", "src/B.sol", "src/Foo.sol", "src/sub/C.sol", "src/sub/deep/D.sol"),
                find("src/**/**/*.sol"),
            )
        }

        @Test
        @Disabled("Semantic divergence (not strictly a bug): our '**'->'.*' fallback lets a non-isolated '**' cross '/', so 'src/**Foo.sol' reaches src/sub/. minimatch/bash treat it as a plain '*'. Product decision.")
        fun `GAP - double-star NOT isolated in a segment should behave like a single star`() {
            // minimatch/bash: '**' only globstars when alone in a segment; 'src/**Foo.sol' is a plain
            // single-segment wildcard, so it must NOT reach into src/sub/.
            Files.writeString(tempDir.resolve("src/BarFoo.sol"), "// same segment")
            Files.createDirectories(tempDir.resolve("src/sub"))
            Files.writeString(tempDir.resolve("src/sub/DeepFoo.sol"), "// nested, must be excluded")
            assertEquals(setOf("src/Foo.sol", "src/BarFoo.sol"), find("src/**Foo.sol"))
        }

        @Test
        fun `dotfiles - leading double-star-slash and hidden directories (product decision)`() {
            // Does '**/*.sol' see files under a hidden '.git'-style directory? Reference libs hide these
            // by default (dot option). This test documents whatever the current behaviour is.
            Files.createDirectories(tempDir.resolve(".hidden"))
            Files.writeString(tempDir.resolve(".hidden/Secret.sol"), "// hidden")
            // Asserting INCLUSION (NIO has no dot-hiding); flip if a dotfile policy is added later.
            assertEquals(allSol + ".hidden/Secret.sol", find("**/*.sol"))
        }
    }

    @Nested
    inner class Prefixes {

        @Test
        fun `explicit glob prefix behaves like an implicit glob`() {
            assertEquals(
                setOf("src/A.sol", "src/B.sol", "src/Foo.sol", "src/sub/C.sol", "src/sub/deep/D.sol"),
                find("glob:src/**/*.sol"),
            )
        }

        @Test
        fun `regex prefix is honoured verbatim`() {
            assertEquals(setOf("test/T.t.sol"), find("regex:.*\\.t\\.sol"))
            assertEquals(setOf("src/A.sol", "src/B.sol"), find("regex:src/[AB]\\.sol"))
        }
    }

    /**
     * [FileReadTools.findFiles] with findHighest=true walks breadth-first and, as soon as a directory
     * yields a match, returns those matches and prunes the whole subtree. The canonical use case is
     * "find every Maven project" by locating the shallowest pom.xml on each branch, never the nested ones.
     */
    @Nested
    inner class FindHighest {

        /** Create a pom.xml at each given relative path under the working dir. */
        private fun poms(vararg rel: String) = rel.forEach {
            val p = tempDir.resolve(it)
            Files.createDirectories(p.parent)
            Files.writeString(p, "<project/>")
        }

        @Test
        fun `a match at the root prunes every deeper match`() {
            poms("pom.xml", "moduleA/pom.xml", "moduleA/sub/pom.xml")
            // The root pom is highest; nothing below it is returned.
            assertEquals(setOf("pom.xml"), find("**/pom.xml", findHighest = true))
        }

        @Test
        fun `the highest match on each independent branch is returned`() {
            poms("moduleA/pom.xml", "moduleA/sub/pom.xml", "moduleB/pom.xml")
            // No root pom, so each top-level module contributes its own highest pom; sub/ is pruned.
            assertEquals(
                setOf("moduleA/pom.xml", "moduleB/pom.xml"),
                find("**/pom.xml", findHighest = true),
            )
        }

        @Test
        fun `findHighest false returns matches at every depth`() {
            poms("moduleA/pom.xml", "moduleA/sub/pom.xml", "moduleB/pom.xml")
            // Contrast with the pruning above: without findHighest, every pom at any depth comes back.
            assertEquals(
                setOf("moduleA/pom.xml", "moduleA/sub/pom.xml", "moduleB/pom.xml"),
                find("**/pom.xml"),
            )
        }

        @Test
        fun `no match returns empty`() {
            poms("moduleA/pom.xml")
            assertEquals(emptySet<String>(), find("**/build.gradle", findHighest = true))
        }
    }

    @Nested
    inner class NoMatches {

        @Test
        fun `unknown extension returns empty`() {
            assertEquals(emptySet<String>(), find("**/*.rs"))
        }

        @Test
        fun `unknown directory returns empty`() {
            assertEquals(emptySet<String>(), find("nope/**/*.sol"))
        }
    }
}
