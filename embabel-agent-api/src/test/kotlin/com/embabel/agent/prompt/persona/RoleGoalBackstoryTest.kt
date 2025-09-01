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
package com.embabel.agent.prompt.persona

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoleGoalBackstoryTest {

    @Test
    fun `should create RoleGoalBackstory with direct constructor`() {
        val roleGoalBackstory = RoleGoalBackstory(
            role = "Software Engineer",
            goal = "Build high-quality applications",
            backstory = "I have 10 years of experience in software development"
        )

        assertEquals("Software Engineer", roleGoalBackstory.role)
        assertEquals("Build high-quality applications", roleGoalBackstory.goal)
        assertEquals("I have 10 years of experience in software development", roleGoalBackstory.backstory)
    }

    @Test
    fun `should create RoleGoalBackstory using fluent builder`() {
        val roleGoalBackstory = RoleGoalBackstory.withRole("Data Scientist")
            .andGoal("Analyze data to drive business decisions")
            .andBackstory("PhD in Statistics with expertise in machine learning")

        assertEquals("Data Scientist", roleGoalBackstory.role)
        assertEquals("Analyze data to drive business decisions", roleGoalBackstory.goal)
        assertEquals("PhD in Statistics with expertise in machine learning", roleGoalBackstory.backstory)
    }

    @Test
    fun `should generate correct prompt contribution`() {
        val roleGoalBackstory = RoleGoalBackstory(
            role = "Technical Writer",
            goal = "Create clear documentation",
            backstory = "Former developer turned writer"
        )

        val contribution = roleGoalBackstory.contribution()
        val expectedContribution = """
            Role: Technical Writer
            Goal: Create clear documentation
            Backstory: Former developer turned writer
        """.trimIndent()

        assertEquals(expectedContribution, contribution)
    }

    @Test
    fun `should handle empty strings in all fields`() {
        val roleGoalBackstory = RoleGoalBackstory(
            role = "",
            goal = "",
            backstory = ""
        )

        assertEquals("", roleGoalBackstory.role)
        assertEquals("", roleGoalBackstory.goal)
        assertEquals("", roleGoalBackstory.backstory)

        val contribution = roleGoalBackstory.contribution()
        // The actual format includes a space after the colon
        assertTrue(contribution.contains("Role: "))
        assertTrue(contribution.contains("Goal: "))
        assertTrue(contribution.contains("Backstory: "))
    }

    @Test
    fun `should handle special characters and newlines`() {
        val roleGoalBackstory = RoleGoalBackstory(
            role = "DevOps\nEngineer",
            goal = "Automate & streamline deployments",
            backstory = "Experience with AWS, Docker & Kubernetes"
        )

        assertEquals("DevOps\nEngineer", roleGoalBackstory.role)
        assertEquals("Automate & streamline deployments", roleGoalBackstory.goal)
        assertEquals("Experience with AWS, Docker & Kubernetes", roleGoalBackstory.backstory)
    }

    @Test
    fun `should handle multiline strings properly`() {
        val roleGoalBackstory = RoleGoalBackstory(
            role = "Product Manager",
            goal = """
                Define product strategy
                Coordinate with stakeholders
                Drive product development
            """.trimIndent(),
            backstory = """
                10+ years in product management
                Led multiple successful product launches
                Strong technical and business background
            """.trimIndent()
        )

        val contribution = roleGoalBackstory.contribution()
        assertTrue(contribution.contains("Define product strategy"))
        assertTrue(contribution.contains("Coordinate with stakeholders"))
        assertTrue(contribution.contains("10+ years in product management"))
    }

    @Test
    fun `should maintain equality for same content`() {
        val roleGoalBackstory1 = RoleGoalBackstory(
            role = "Designer",
            goal = "Create beautiful UIs",
            backstory = "5 years of design experience"
        )
        val roleGoalBackstory2 = RoleGoalBackstory(
            role = "Designer",
            goal = "Create beautiful UIs",
            backstory = "5 years of design experience"
        )

        assertEquals(roleGoalBackstory1, roleGoalBackstory2)
        assertEquals(roleGoalBackstory1.hashCode(), roleGoalBackstory2.hashCode())
    }

    @Test
    fun `should not be equal for different content`() {
        val roleGoalBackstory1 = RoleGoalBackstory(
            role = "Designer",
            goal = "Create beautiful UIs",
            backstory = "5 years of design experience"
        )
        val roleGoalBackstory2 = RoleGoalBackstory(
            role = "Developer",
            goal = "Create beautiful UIs",
            backstory = "5 years of design experience"
        )

        assertNotEquals(roleGoalBackstory1, roleGoalBackstory2)
    }

    @Test
    fun `should have proper toString representation`() {
        val roleGoalBackstory = RoleGoalBackstory(
            role = "QA Engineer",
            goal = "Ensure software quality",
            backstory = "Experienced in automation testing"
        )

        val toString = roleGoalBackstory.toString()
        assertTrue(toString.contains("QA Engineer"))
        assertTrue(toString.contains("Ensure software quality"))
        assertTrue(toString.contains("Experienced in automation testing"))
    }

    @Test
    fun `should support copy functionality`() {
        val original = RoleGoalBackstory(
            role = "Original Role",
            goal = "Original Goal",
            backstory = "Original Backstory"
        )

        val copied = original.copy(role = "New Role")

        assertEquals("New Role", copied.role)
        assertEquals("Original Goal", copied.goal)
        assertEquals("Original Backstory", copied.backstory)
        assertNotEquals(original, copied)
    }

    @Test
    fun `builder should chain methods correctly`() {
        val roleBuilder = RoleGoalBackstory.withRole("Test Role")
        assertNotNull(roleBuilder)

        val goalBuilder = roleBuilder.andGoal("Test Goal")
        assertNotNull(goalBuilder)

        val finalBackstory = goalBuilder.andBackstory("Test Backstory")
        assertNotNull(finalBackstory)
        assertEquals("Test Role", finalBackstory.role)
        assertEquals("Test Goal", finalBackstory.goal)
        assertEquals("Test Backstory", finalBackstory.backstory)
    }

    @Test
    fun `should handle very long text content`() {
        val longText = "A".repeat(1000)
        val roleGoalBackstory = RoleGoalBackstory(
            role = longText,
            goal = longText,
            backstory = longText
        )

        assertEquals(longText, roleGoalBackstory.role)
        assertEquals(longText, roleGoalBackstory.goal)
        assertEquals(longText, roleGoalBackstory.backstory)

        val contribution = roleGoalBackstory.contribution()
        assertTrue(contribution.contains(longText))
    }
}
