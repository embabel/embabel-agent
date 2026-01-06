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
package com.embabel.agent.skills

import com.embabel.agent.skills.spec.SkillDefinition
import com.embabel.agent.skills.support.ClaudeFrontMatterFormatter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClaudeFrontMatterFormatterTest {

    private val formatter = ClaudeFrontMatterFormatter

    @Test
    fun `format empty list returns empty available_skills element`() {
        val result = formatter.format(emptyList())

        assertEquals("<available_skills>\n</available_skills>", result)
    }

    @Test
    fun `format single skill returns correct XML structure`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "A helpful skill for testing",
        )

        val result = formatter.format(listOf(skill))

        val expected = """
            |<available_skills>
            |  <skill>
            |    <name>my-skill</name>
            |    <description>A helpful skill for testing</description>
            |  </skill>
            |</available_skills>
        """.trimMargin()

        assertEquals(expected, result)
    }

    @Test
    fun `format multiple skills returns all skills in XML`() {
        val skills = listOf(
            SkillDefinition(name = "skill-one", description = "First skill"),
            SkillDefinition(name = "skill-two", description = "Second skill"),
            SkillDefinition(name = "skill-three", description = "Third skill"),
        )

        val result = formatter.format(skills)

        assertTrue(result.startsWith("<available_skills>"))
        assertTrue(result.endsWith("</available_skills>"))
        assertTrue(result.contains("<name>skill-one</name>"))
        assertTrue(result.contains("<name>skill-two</name>"))
        assertTrue(result.contains("<name>skill-three</name>"))
        assertTrue(result.contains("<description>First skill</description>"))
        assertTrue(result.contains("<description>Second skill</description>"))
        assertTrue(result.contains("<description>Third skill</description>"))
    }

    @Test
    fun `formatSkill returns correct XML for single skill`() {
        val skill = SkillDefinition(
            name = "test-skill",
            description = "Test description",
        )

        val result = formatter.formatSkill(skill)

        val expected = """
            |<skill>
            |  <name>test-skill</name>
            |  <description>Test description</description>
            |</skill>
        """.trimMargin()

        assertEquals(expected, result)
    }

    @Test
    fun `format escapes XML special characters in name`() {
        val skill = SkillDefinition(
            name = "skill<with>special&chars",
            description = "Normal description",
        )

        val result = formatter.formatSkill(skill)

        assertTrue(result.contains("skill&lt;with&gt;special&amp;chars"))
        assertFalse(result.contains("<with>"))
    }

    @Test
    fun `format escapes XML special characters in description`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Use <tag> and \"quotes\" with 'apostrophes' & ampersands",
        )

        val result = formatter.formatSkill(skill)

        assertTrue(result.contains("&lt;tag&gt;"))
        assertTrue(result.contains("&quot;quotes&quot;"))
        assertTrue(result.contains("&apos;apostrophes&apos;"))
        assertTrue(result.contains("&amp;"))
        assertFalse(result.contains("<tag>"))
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
        assertTrue(result.contains("<name>full-skill</name>"))
        assertTrue(result.contains("<description>Has all optional fields</description>"))
        assertFalse(result.contains("license"))
        assertFalse(result.contains("compatibility"))
        assertFalse(result.contains("metadata"))
        assertFalse(result.contains("allowed"))
        assertFalse(result.contains("instructions"))
        assertFalse(result.contains("Apache"))
        assertFalse(result.contains("Python"))
    }

    @Test
    fun `format produces valid XML structure`() {
        val skills = listOf(
            SkillDefinition(name = "skill-a", description = "Description A"),
            SkillDefinition(name = "skill-b", description = "Description B"),
        )

        val result = formatter.format(skills)

        // Count opening and closing tags
        assertEquals(1, result.split("<available_skills>").size - 1)
        assertEquals(1, result.split("</available_skills>").size - 1)
        assertEquals(2, result.split("<skill>").size - 1)
        assertEquals(2, result.split("</skill>").size - 1)
        assertEquals(2, result.split("<name>").size - 1)
        assertEquals(2, result.split("</name>").size - 1)
        assertEquals(2, result.split("<description>").size - 1)
        assertEquals(2, result.split("</description>").size - 1)
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
