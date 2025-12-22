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
package com.embabel.agent.skills

import com.embabel.agent.skills.spec.SkillDefinition
import com.embabel.agent.skills.support.CursorFrontMatterFormatter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CursorFrontMatterFormatterTest {

    private val formatter = CursorFrontMatterFormatter

    @Test
    fun `format empty list returns no skills available message`() {
        val result = formatter.format(emptyList())

        assertEquals("## Available Skills\n\nNo skills available.", result)
    }

    @Test
    fun `format single skill returns correct markdown structure`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "A helpful skill for testing",
        )

        val result = formatter.format(listOf(skill))

        val expected = """
            |## Available Skills
            |
            |- **my-skill**: A helpful skill for testing
        """.trimMargin()

        assertEquals(expected, result)
    }

    @Test
    fun `format multiple skills returns all skills in markdown`() {
        val skills = listOf(
            SkillDefinition(name = "skill-one", description = "First skill"),
            SkillDefinition(name = "skill-two", description = "Second skill"),
            SkillDefinition(name = "skill-three", description = "Third skill"),
        )

        val result = formatter.format(skills)

        assertTrue(result.startsWith("## Available Skills"))
        assertTrue(result.contains("- **skill-one**: First skill"))
        assertTrue(result.contains("- **skill-two**: Second skill"))
        assertTrue(result.contains("- **skill-three**: Third skill"))
    }

    @Test
    fun `formatSkill returns correct markdown for single skill`() {
        val skill = SkillDefinition(
            name = "test-skill",
            description = "Test description",
        )

        val result = formatter.formatSkill(skill)

        assertEquals("- **test-skill**: Test description", result)
    }

    @Test
    fun `format escapes markdown special characters in name`() {
        val skill = SkillDefinition(
            name = "skill*with_special`chars",
            description = "Normal description",
        )

        val result = formatter.formatSkill(skill)

        assertTrue(result.contains("skill\\*with\\_special\\`chars"))
        assertFalse(result.contains("skill*with"))
    }

    @Test
    fun `format escapes markdown special characters in description`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Use **bold** and _italic_ with `code` and [links]",
        )

        val result = formatter.formatSkill(skill)

        assertTrue(result.contains("\\*\\*bold\\*\\*"))
        assertTrue(result.contains("\\_italic\\_"))
        assertTrue(result.contains("\\`code\\`"))
        assertTrue(result.contains("\\[links\\]"))
        assertFalse(result.contains("**bold**"))
    }

    @Test
    fun `format escapes backslashes`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Path like C:\\Users\\name",
        )

        val result = formatter.formatSkill(skill)

        assertTrue(result.contains("C:\\\\Users\\\\name"))
    }

    @Test
    fun `format does not include optional fields`() {
        val skill = SkillDefinition(
            name = "full-skill",
            description = "Has all optional fields",
            license = "Apache-2.0",
            compatibility = "Requires Python 3.9+",
            metadata = mapOf("author" to "test"),
            allowedTools = "Bash(git:*) Read",
            instructions = "# Full Instructions\n\nLots of detail here...",
        )

        val result = formatter.formatSkill(skill)

        // Should only contain name and description, not other fields
        assertTrue(result.contains("**full-skill**"))
        assertTrue(result.contains("Has all optional fields"))
        assertFalse(result.contains("license"))
        assertFalse(result.contains("compatibility"))
        assertFalse(result.contains("metadata"))
        assertFalse(result.contains("allowed"))
        assertFalse(result.contains("instructions"))
        assertFalse(result.contains("Apache"))
        assertFalse(result.contains("Python"))
    }

    @Test
    fun `format produces valid markdown structure`() {
        val skills = listOf(
            SkillDefinition(name = "skill-a", description = "Description A"),
            SkillDefinition(name = "skill-b", description = "Description B"),
        )

        val result = formatter.format(skills)

        // Should have header and two bullet points
        assertTrue(result.startsWith("## Available Skills"))
        assertEquals(2, result.split("\n- **").size - 1)
    }

    @Test
    fun `format handles multiline description`() {
        val skill = SkillDefinition(
            name = "multiline-skill",
            description = "First line\nSecond line\nThird line",
        )

        val result = formatter.formatSkill(skill)

        assertTrue(result.contains("First line\nSecond line\nThird line"))
    }
}
