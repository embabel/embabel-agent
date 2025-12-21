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
import com.embabel.agent.skills.support.DefaultSkillValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultSkillValidatorTest {

    private val validator = DefaultSkillValidator()

    @Test
    fun `valid skill passes validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "A useful skill for doing things",
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `valid skill with all optional fields passes validation`() {
        val skill = SkillDefinition(
            name = "complete-skill",
            description = "A complete skill with all fields",
            license = "Apache-2.0",
            compatibility = "Requires network access",
            metadata = mapOf("author" to "test"),
            allowedTools = "Bash(git:*) Read",
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    // Name validation tests

    @Test
    fun `empty name fails validation`() {
        val skill = SkillDefinition(name = "", description = "Valid description")

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("required") })
    }

    @Test
    fun `name exceeding 64 characters fails validation`() {
        val skill = SkillDefinition(
            name = "a".repeat(65),
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("64") })
    }

    @Test
    fun `name with exactly 64 characters passes validation`() {
        val skill = SkillDefinition(
            name = "a".repeat(64),
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
    }

    @Test
    fun `name with uppercase characters fails validation`() {
        val skill = SkillDefinition(
            name = "My-Skill",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("lowercase") })
    }

    @Test
    fun `name with spaces fails validation`() {
        val skill = SkillDefinition(
            name = "my skill",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" })
    }

    @Test
    fun `name with underscores fails validation`() {
        val skill = SkillDefinition(
            name = "my_skill",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" })
    }

    @Test
    fun `name starting with hyphen fails validation`() {
        val skill = SkillDefinition(
            name = "-my-skill",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("start") })
    }

    @Test
    fun `name ending with hyphen fails validation`() {
        val skill = SkillDefinition(
            name = "my-skill-",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("end") })
    }

    @Test
    fun `name with consecutive hyphens fails validation`() {
        val skill = SkillDefinition(
            name = "my--skill",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("consecutive") })
    }

    @Test
    fun `name not matching directory name fails validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Valid description",
        )

        val result = validator.validate(skill, directoryName = "different-name")

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "name" && it.message.contains("directory") })
    }

    @Test
    fun `name matching directory name passes validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Valid description",
        )

        val result = validator.validate(skill, directoryName = "my-skill")

        assertTrue(result.isValid)
    }

    @Test
    fun `name with numbers is valid`() {
        val skill = SkillDefinition(
            name = "skill-v2",
            description = "Valid description",
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
    }

    // Description validation tests

    @Test
    fun `empty description fails validation`() {
        val skill = SkillDefinition(name = "my-skill", description = "")

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "description" && it.message.contains("required") })
    }

    @Test
    fun `description exceeding 1024 characters fails validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "a".repeat(1025),
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "description" && it.message.contains("1024") })
    }

    @Test
    fun `description with exactly 1024 characters passes validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "a".repeat(1024),
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
    }

    // Compatibility validation tests

    @Test
    fun `empty compatibility fails validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Valid description",
            compatibility = "",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "compatibility" && it.message.contains("empty") })
    }

    @Test
    fun `compatibility exceeding 500 characters fails validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Valid description",
            compatibility = "a".repeat(501),
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "compatibility" && it.message.contains("500") })
    }

    @Test
    fun `compatibility with exactly 500 characters passes validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Valid description",
            compatibility = "a".repeat(500),
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
    }

    @Test
    fun `null compatibility passes validation`() {
        val skill = SkillDefinition(
            name = "my-skill",
            description = "Valid description",
            compatibility = null,
        )

        val result = validator.validate(skill)

        assertTrue(result.isValid)
    }

    // Multiple errors test

    @Test
    fun `multiple validation errors are collected`() {
        val skill = SkillDefinition(
            name = "My--Skill-",
            description = "",
            compatibility = "",
        )

        val result = validator.validate(skill)

        assertFalse(result.isValid)
        assertTrue(result.errors.size >= 3)
        assertTrue(result.errors.any { it.field == "name" })
        assertTrue(result.errors.any { it.field == "description" })
        assertTrue(result.errors.any { it.field == "compatibility" })
    }
}
