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
package com.embabel.agent.rag.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DirectoryParsingConfig to verify no-arg constructor and wither methods.
 */
@DisplayName("DirectoryParsingConfig Tests")
class DirectoryParsingConfigTest {

    @Nested
    @DisplayName("No-Arg Constructor Tests")
    class NoArgConstructorTests {

        @Test
        @DisplayName("should create config with default values")
        void shouldCreateConfigWithDefaults() {
            com.embabel.agent.rag.ingestion.DirectoryParsingConfig config = new DirectoryParsingConfig();

            assertNotNull(config.getIncludedExtensions());
            assertFalse(config.getIncludedExtensions().isEmpty());
            assertTrue(config.getIncludedExtensions().contains("md"));
            assertTrue(config.getIncludedExtensions().contains("txt"));
            assertTrue(config.getIncludedExtensions().contains("java"));
            assertTrue(config.getIncludedExtensions().contains("kt"));

            assertNotNull(config.getExcludedDirectories());
            assertFalse(config.getExcludedDirectories().isEmpty());
            assertTrue(config.getExcludedDirectories().contains(".git"));
            assertTrue(config.getExcludedDirectories().contains("node_modules"));
            assertTrue(config.getExcludedDirectories().contains("target"));

            assertEquals("", config.getRelativePath());
            assertEquals(10 * 1024 * 1024L, config.getMaxFileSize()); // 10MB
            assertFalse(config.getFollowSymlinks());
            assertEquals(Integer.MAX_VALUE, config.getMaxDepth());
        }

        @Test
        @DisplayName("should have expected default included extensions")
        void shouldHaveExpectedDefaultIncludedExtensions() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();

            // Text formats
            assertTrue(config.getIncludedExtensions().contains("txt"));
            assertTrue(config.getIncludedExtensions().contains("md"));
            assertTrue(config.getIncludedExtensions().contains("rst"));

            // Code files
            assertTrue(config.getIncludedExtensions().contains("java"));
            assertTrue(config.getIncludedExtensions().contains("kt"));
            assertTrue(config.getIncludedExtensions().contains("py"));
            assertTrue(config.getIncludedExtensions().contains("js"));
            assertTrue(config.getIncludedExtensions().contains("ts"));

            // Document formats
            assertTrue(config.getIncludedExtensions().contains("pdf"));
            assertTrue(config.getIncludedExtensions().contains("docx"));
        }

        @Test
        @DisplayName("should have expected default excluded directories")
        void shouldHaveExpectedDefaultExcludedDirectories() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();

            // Version control
            assertTrue(config.getExcludedDirectories().contains(".git"));
            assertTrue(config.getExcludedDirectories().contains(".svn"));

            // Build directories
            assertTrue(config.getExcludedDirectories().contains("target"));
            assertTrue(config.getExcludedDirectories().contains("build"));
            assertTrue(config.getExcludedDirectories().contains("dist"));

            // Dependencies
            assertTrue(config.getExcludedDirectories().contains("node_modules"));
            assertTrue(config.getExcludedDirectories().contains(".gradle"));
            assertTrue(config.getExcludedDirectories().contains(".m2"));

            // IDE directories
            assertTrue(config.getExcludedDirectories().contains(".idea"));
            assertTrue(config.getExcludedDirectories().contains(".vscode"));
        }
    }

    @Nested
    @DisplayName("withRelativePath Tests")
    class WithRelativePathTests {

        @Test
        @DisplayName("should set new relative path")
        void shouldSetNewRelativePath() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withRelativePath("docs");

            assertEquals("docs", updated.getRelativePath());
        }

        @Test
        @DisplayName("should not modify original config")
        void shouldNotModifyOriginalConfig() {
            DirectoryParsingConfig original = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = original.withRelativePath("src/main");

            assertEquals("", original.getRelativePath());
            assertEquals("src/main", updated.getRelativePath());
            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("should preserve other properties")
        void shouldPreserveOtherProperties() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withRelativePath("project/docs");

            assertEquals(config.getMaxFileSize(), updated.getMaxFileSize());
            assertEquals(config.getFollowSymlinks(), updated.getFollowSymlinks());
            assertEquals(config.getMaxDepth(), updated.getMaxDepth());
            assertEquals(config.getIncludedExtensions(), updated.getIncludedExtensions());
            assertEquals(config.getExcludedDirectories(), updated.getExcludedDirectories());
        }

        @Test
        @DisplayName("should handle nested paths")
        void shouldHandleNestedPaths() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withRelativePath("a/b/c/d");

            assertEquals("a/b/c/d", updated.getRelativePath());
        }

        @Test
        @DisplayName("should handle empty string")
        void shouldHandleEmptyString() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withRelativePath("temp");
            DirectoryParsingConfig updated = config.withRelativePath("");

            assertEquals("", updated.getRelativePath());
        }
    }

    @Nested
    @DisplayName("withMaxFileSize Tests")
    class WithMaxFileSizeTests {

        @Test
        @DisplayName("should set new max file size")
        void shouldSetNewMaxFileSize() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxFileSize(5 * 1024 * 1024L);

            assertEquals(5 * 1024 * 1024L, updated.getMaxFileSize());
        }

        @Test
        @DisplayName("should not modify original config")
        void shouldNotModifyOriginalConfig() {
            DirectoryParsingConfig original = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = original.withMaxFileSize(1024L);

            assertEquals(10 * 1024 * 1024L, original.getMaxFileSize());
            assertEquals(1024L, updated.getMaxFileSize());
            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("should handle very small file size")
        void shouldHandleVerySmallFileSize() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxFileSize(100L);

            assertEquals(100L, updated.getMaxFileSize());
        }

        @Test
        @DisplayName("should handle very large file size")
        void shouldHandleVeryLargeFileSize() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            long veryLarge = 1024L * 1024L * 1024L * 10L; // 10GB
            DirectoryParsingConfig updated = config.withMaxFileSize(veryLarge);

            assertEquals(veryLarge, updated.getMaxFileSize());
        }

        @Test
        @DisplayName("should preserve other properties")
        void shouldPreserveOtherProperties() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxFileSize(2048L);

            assertEquals(config.getRelativePath(), updated.getRelativePath());
            assertEquals(config.getFollowSymlinks(), updated.getFollowSymlinks());
            assertEquals(config.getMaxDepth(), updated.getMaxDepth());
        }
    }

    @Nested
    @DisplayName("withFollowSymlinks Tests")
    class WithFollowSymlinksTests {

        @Test
        @DisplayName("should enable symlink following")
        void shouldEnableSymlinkFollowing() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withFollowSymlinks(true);

            assertTrue(updated.getFollowSymlinks());
        }

        @Test
        @DisplayName("should disable symlink following")
        void shouldDisableSymlinkFollowing() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withFollowSymlinks(true);
            DirectoryParsingConfig updated = config.withFollowSymlinks(false);

            assertFalse(updated.getFollowSymlinks());
        }

        @Test
        @DisplayName("should not modify original config")
        void shouldNotModifyOriginalConfig() {
            DirectoryParsingConfig original = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = original.withFollowSymlinks(true);

            assertFalse(original.getFollowSymlinks());
            assertTrue(updated.getFollowSymlinks());
            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("should preserve other properties")
        void shouldPreserveOtherProperties() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withFollowSymlinks(true);

            assertEquals(config.getRelativePath(), updated.getRelativePath());
            assertEquals(config.getMaxFileSize(), updated.getMaxFileSize());
            assertEquals(config.getMaxDepth(), updated.getMaxDepth());
        }
    }

    @Nested
    @DisplayName("withMaxDepth Tests")
    class WithMaxDepthTests {

        @Test
        @DisplayName("should set new max depth")
        void shouldSetNewMaxDepth() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxDepth(5);

            assertEquals(5, updated.getMaxDepth());
        }

        @Test
        @DisplayName("should not modify original config")
        void shouldNotModifyOriginalConfig() {
            DirectoryParsingConfig original = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = original.withMaxDepth(3);

            assertEquals(Integer.MAX_VALUE, original.getMaxDepth());
            assertEquals(3, updated.getMaxDepth());
            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("should handle depth of zero")
        void shouldHandleDepthOfZero() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxDepth(0);

            assertEquals(0, updated.getMaxDepth());
        }

        @Test
        @DisplayName("should handle depth of one")
        void shouldHandleDepthOfOne() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxDepth(1);

            assertEquals(1, updated.getMaxDepth());
        }

        @Test
        @DisplayName("should handle large depth")
        void shouldHandleLargeDepth() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxDepth(1000);

            assertEquals(1000, updated.getMaxDepth());
        }

        @Test
        @DisplayName("should preserve other properties")
        void shouldPreserveOtherProperties() {
            DirectoryParsingConfig config = new DirectoryParsingConfig();
            DirectoryParsingConfig updated = config.withMaxDepth(10);

            assertEquals(config.getRelativePath(), updated.getRelativePath());
            assertEquals(config.getMaxFileSize(), updated.getMaxFileSize());
            assertEquals(config.getFollowSymlinks(), updated.getFollowSymlinks());
        }
    }

    @Nested
    @DisplayName("Chaining Wither Methods")
    class ChainingWitherMethodsTests {

        @Test
        @DisplayName("should chain withRelativePath and withMaxFileSize")
        void shouldChainRelativePathAndMaxFileSize() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withRelativePath("docs")
                    .withMaxFileSize(1024L);

            assertEquals("docs", config.getRelativePath());
            assertEquals(1024L, config.getMaxFileSize());
        }

        @Test
        @DisplayName("should chain all wither methods")
        void shouldChainAllWitherMethods() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withRelativePath("src/main/java")
                    .withMaxFileSize(5 * 1024 * 1024L)
                    .withFollowSymlinks(true)
                    .withMaxDepth(10);

            assertEquals("src/main/java", config.getRelativePath());
            assertEquals(5 * 1024 * 1024L, config.getMaxFileSize());
            assertTrue(config.getFollowSymlinks());
            assertEquals(10, config.getMaxDepth());
        }

        @Test
        @DisplayName("should chain wither methods in any order")
        void shouldChainWitherMethodsInAnyOrder() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withMaxDepth(3)
                    .withFollowSymlinks(true)
                    .withRelativePath("test")
                    .withMaxFileSize(2048L);

            assertEquals("test", config.getRelativePath());
            assertEquals(2048L, config.getMaxFileSize());
            assertTrue(config.getFollowSymlinks());
            assertEquals(3, config.getMaxDepth());
        }

        @Test
        @DisplayName("should allow repeated updates to same property")
        void shouldAllowRepeatedUpdates() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withRelativePath("first")
                    .withRelativePath("second")
                    .withRelativePath("third");

            assertEquals("third", config.getRelativePath());
        }

        @Test
        @DisplayName("should maintain immutability through chaining")
        void shouldMaintainImmutabilityThroughChaining() {
            DirectoryParsingConfig original = new DirectoryParsingConfig();
            DirectoryParsingConfig step1 = original.withRelativePath("step1");
            DirectoryParsingConfig step2 = step1.withMaxFileSize(1024L);
            DirectoryParsingConfig step3 = step2.withFollowSymlinks(true);

            // Verify each step is a new instance
            assertNotSame(original, step1);
            assertNotSame(step1, step2);
            assertNotSame(step2, step3);

            // Verify original is unchanged
            assertEquals("", original.getRelativePath());
            assertEquals(10 * 1024 * 1024L, original.getMaxFileSize());
            assertFalse(original.getFollowSymlinks());
        }
    }

    @Nested
    @DisplayName("Builder Pattern Usage")
    class BuilderPatternUsageTests {

        @Test
        @DisplayName("should support builder pattern for docs directory")
        void shouldSupportBuilderPatternForDocsDirectory() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withRelativePath("docs")
                    .withMaxFileSize(2 * 1024 * 1024L)
                    .withMaxDepth(5);

            assertEquals("docs", config.getRelativePath());
            assertEquals(2 * 1024 * 1024L, config.getMaxFileSize());
            assertEquals(5, config.getMaxDepth());
            assertFalse(config.getFollowSymlinks()); // Default value preserved
        }

        @Test
        @DisplayName("should support builder pattern for source code directory")
        void shouldSupportBuilderPatternForSourceCode() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withRelativePath("src/main/java")
                    .withMaxFileSize(1024 * 1024L)
                    .withFollowSymlinks(false)
                    .withMaxDepth(Integer.MAX_VALUE);

            assertEquals("src/main/java", config.getRelativePath());
            assertEquals(1024 * 1024L, config.getMaxFileSize());
            assertFalse(config.getFollowSymlinks());
            assertEquals(Integer.MAX_VALUE, config.getMaxDepth());
        }

        @Test
        @DisplayName("should support builder pattern for shallow scan")
        void shouldSupportBuilderPatternForShallowScan() {
            DirectoryParsingConfig config = new DirectoryParsingConfig()
                    .withMaxDepth(1)
                    .withFollowSymlinks(false);

            assertEquals(1, config.getMaxDepth());
            assertFalse(config.getFollowSymlinks());
            assertEquals("", config.getRelativePath()); // Default value preserved
        }
    }

    @Nested
    @DisplayName("Equality and HashCode")
    class EqualityAndHashCodeTests {

        @Test
        @DisplayName("should be equal when all properties match")
        void shouldBeEqualWhenAllPropertiesMatch() {
            DirectoryParsingConfig config1 = new DirectoryParsingConfig()
                    .withRelativePath("test")
                    .withMaxFileSize(1024L)
                    .withFollowSymlinks(true)
                    .withMaxDepth(5);

            DirectoryParsingConfig config2 = new DirectoryParsingConfig()
                    .withRelativePath("test")
                    .withMaxFileSize(1024L)
                    .withFollowSymlinks(true)
                    .withMaxDepth(5);

            assertEquals(config1, config2);
            assertEquals(config1.hashCode(), config2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when relativePath differs")
        void shouldNotBeEqualWhenRelativePathDiffers() {
            DirectoryParsingConfig config1 = new DirectoryParsingConfig()
                    .withRelativePath("path1");

            DirectoryParsingConfig config2 = new DirectoryParsingConfig()
                    .withRelativePath("path2");

            assertNotEquals(config1, config2);
        }

        @Test
        @DisplayName("should not be equal when maxFileSize differs")
        void shouldNotBeEqualWhenMaxFileSizeDiffers() {
            DirectoryParsingConfig config1 = new DirectoryParsingConfig()
                    .withMaxFileSize(1024L);

            DirectoryParsingConfig config2 = new DirectoryParsingConfig()
                    .withMaxFileSize(2048L);

            assertNotEquals(config1, config2);
        }

        @Test
        @DisplayName("should not be equal when followSymlinks differs")
        void shouldNotBeEqualWhenFollowSymlinksDiffers() {
            DirectoryParsingConfig config1 = new DirectoryParsingConfig()
                    .withFollowSymlinks(true);

            DirectoryParsingConfig config2 = new DirectoryParsingConfig()
                    .withFollowSymlinks(false);

            assertNotEquals(config1, config2);
        }

        @Test
        @DisplayName("should not be equal when maxDepth differs")
        void shouldNotBeEqualWhenMaxDepthDiffers() {
            DirectoryParsingConfig config1 = new DirectoryParsingConfig()
                    .withMaxDepth(5);

            DirectoryParsingConfig config2 = new DirectoryParsingConfig()
                    .withMaxDepth(10);

            assertNotEquals(config1, config2);
        }
    }
}
