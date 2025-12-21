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
package com.embabel.agent.skills.support

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GitHubUrlParsingTest {

    @Test
    fun `parses simple repo URL`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl("https://github.com/owner/repo")

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
        assertNull(result.branch)
        assertNull(result.path)
    }

    @Test
    fun `parses repo URL with trailing slash`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl("https://github.com/owner/repo/")

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
        assertNull(result.branch)
        assertNull(result.path)
    }

    @Test
    fun `parses tree URL with branch only`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl("https://github.com/owner/repo/tree/main")

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
        assertEquals("main", result.branch)
        assertNull(result.path)
    }

    @Test
    fun `parses tree URL with branch and path`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl(
            "https://github.com/wshobson/agents/tree/main/plugins/business-analytics/skills"
        )

        assertEquals("wshobson", result.owner)
        assertEquals("agents", result.repo)
        assertEquals("main", result.branch)
        assertEquals("plugins/business-analytics/skills", result.path)
    }

    @Test
    fun `parses blob URL with branch and path`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl(
            "https://github.com/wshobson/agents/blob/main/plugins/business-analytics/skills/data-storytelling"
        )

        assertEquals("wshobson", result.owner)
        assertEquals("agents", result.repo)
        assertEquals("main", result.branch)
        assertEquals("plugins/business-analytics/skills/data-storytelling", result.path)
    }

    @Test
    fun `parses URL with trailing slash on path`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl(
            "https://github.com/owner/repo/tree/develop/skills/my-skill/"
        )

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
        assertEquals("develop", result.branch)
        assertEquals("skills/my-skill", result.path)
    }

    @Test
    fun `parses http URL`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl("http://github.com/owner/repo")

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
    }

    @Test
    fun `removes git suffix from repo name`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl("https://github.com/owner/repo.git")

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
    }

    @Test
    fun `parses anthropic skills repo URL`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl(
            "https://github.com/anthropics/skills/tree/main/skills"
        )

        assertEquals("anthropics", result.owner)
        assertEquals("skills", result.repo)
        assertEquals("main", result.branch)
        assertEquals("skills", result.path)
    }

    @Test
    fun `throws exception for non-GitHub URL`() {
        val exception = assertThrows(SkillLoadException::class.java) {
            GitHubSkillDefinitionLoader.parseGitHubUrl("https://gitlab.com/owner/repo")
        }

        assertTrue(exception.message!!.contains("Invalid GitHub URL"))
    }

    @Test
    fun `throws exception for malformed URL`() {
        val exception = assertThrows(SkillLoadException::class.java) {
            GitHubSkillDefinitionLoader.parseGitHubUrl("not-a-url")
        }

        assertTrue(exception.message!!.contains("Invalid GitHub URL"))
    }

    @Test
    fun `throws exception for URL with only owner`() {
        val exception = assertThrows(SkillLoadException::class.java) {
            GitHubSkillDefinitionLoader.parseGitHubUrl("https://github.com/owner")
        }

        assertTrue(exception.message!!.contains("Invalid GitHub URL"))
    }

    @Test
    fun `parses URL with feature branch name`() {
        val result = GitHubSkillDefinitionLoader.parseGitHubUrl(
            "https://github.com/owner/repo/tree/feature/my-feature/path/to/skills"
        )

        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
        assertEquals("feature", result.branch)
        // Note: the regex captures only the first path segment as branch
        // This is a limitation - feature/my-feature becomes just "feature"
    }
}
