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
package com.embabel.common.ai.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 *  @see [PromptContributor]
 *  The test covers:
 *
 *   1. Core PromptContributor functionality:
 *     - Default role (class name) and location (BEGINNING) behavior
 *     - Ability to override role and location
 *     - The factory method for creating fixed contributors
 *   2. KnowledgeCutoffDate implementation:
 *     - Correct date formatting
 *     - Use of custom formatters
 *     - Role assignment
 *   3. CurrentDate implementation:
 *     - Current date inclusion
 *     - Custom formatters
 *     - Role assignment
 *   4. toString method implementations for all classes:
 *     - Including class names
 *     - Including contribution content
 */
class PromptContributorTest {
    
    @Test
    fun `PromptContributor should use class name as default role`() {
        // Create a custom implementation with no role override
        val contributor = object : PromptContributor {
            override fun contribution(): String = "Test content"
        }
        
        // Get the prompt contribution
        val contribution = contributor.promptContribution()
        
        // Role should be the simple class name of the anonymous class
        assertEquals(contributor.javaClass.simpleName, contribution.role)
    }
    
    @Test
    fun `PromptContributor should use BEGINNING as default location`() {
        // Create a custom implementation with no location override
        val contributor = object : PromptContributor {
            override fun contribution(): String = "Test content"
        }
        
        // Get the prompt contribution
        val contribution = contributor.promptContribution()
        
        // Location should be BEGINNING
        assertEquals(PromptContributionLocation.BEGINNING, contribution.location)
    }
    
    @Test
    fun `PromptContributor should allow overriding role and location`() {
        // Create a custom implementation with role and location overrides
        val contributor = object : PromptContributor {
            override val role: String? = "custom_role"
            override val promptContributionLocation = PromptContributionLocation.END
            override fun contribution(): String = "Test content"
        }
        
        // Get the prompt contribution
        val contribution = contributor.promptContribution()
        
        // Check that overrides are respected
        assertEquals("custom_role", contribution.role)
        assertEquals(PromptContributionLocation.END, contribution.location)
    }
    
    @Test
    fun `fixed factory method should create contributor with correct properties`() {
        // Test with default parameters
        val defaultContributor = PromptContributor.fixed("Default content")
        val defaultContribution = defaultContributor.promptContribution()
        
        assertEquals("Default content", defaultContribution.content)
        assertNull(defaultContribution.role)
        assertEquals(PromptContributionLocation.BEGINNING, defaultContribution.location)
        
        // Test with custom parameters
        val customContributor = PromptContributor.fixed(
            content = "Custom content",
            role = "custom_role",
            location = PromptContributionLocation.END
        )
        val customContribution = customContributor.promptContribution()
        
        assertEquals("Custom content", customContribution.content)
        assertEquals("custom_role", customContribution.role)
        assertEquals(PromptContributionLocation.END, customContribution.location)
    }
    
    @Test
    fun `KnowledgeCutoffDate should format date correctly`() {
        // Create with a specific date
        val date = LocalDate.of(2025, 4, 1)
        val cutoffDate = KnowledgeCutoffDate(date)
        
        // Check content
        val content = cutoffDate.contribution()
        assertTrue(content.contains("Knowledge cutoff: 2025-04"), "Content should contain formatted date: $content")
        
        // Check role
        assertEquals(PromptContribution.KNOWLEDGE_CUTOFF_ROLE, cutoffDate.role)
    }
    
    @Test
    fun `KnowledgeCutoffDate should respect custom formatter`() {
        // Create with a custom formatter
        val date = LocalDate.of(2025, 4, 1)
        val formatter = DateTimeFormatter.ofPattern("MMMM yyyy")
        val cutoffDate = KnowledgeCutoffDate(date, formatter)
        
        // Check content uses custom format
        val content = cutoffDate.contribution()
        assertTrue(content.contains("Knowledge cutoff: April 2025"),
            "Content should use custom formatter: $content")
    }
    
    @Test
    fun `CurrentDate should include current date`() {
        // Create current date contributor
        val currentDate = CurrentDate()
        
        // Get content
        val content = currentDate.contribution()
        
        // Should contain "Current date:" followed by today's date
        assertTrue(content.startsWith("Current date: "), 
            "Content should start with 'Current date: ': $content")
        
        // Should contain today's date in the format YYYY-MM-DD
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        assertTrue(content.contains(today), 
            "Content should contain today's date ($today): $content")
        
        // Check role
        assertEquals(PromptContribution.CURRENT_DATE_ROLE, currentDate.role)
    }
    
    @Test
    fun `CurrentDate should respect custom formatter`() {
        // Create with custom formatter
        val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        val currentDate = CurrentDate(formatter)
        
        // Get content
        val content = currentDate.contribution()
        
        // Should contain today's date in the custom format MM/DD/YYYY
        val today = LocalDate.now().format(formatter)
        assertTrue(content.contains(today), 
            "Content should contain today's date in custom format ($today): $content")
    }
    
    @Test
    fun `toString implementations should include contribution content`() {
        // Test KnowledgeCutoffDate toString
        val date = LocalDate.of(2025, 4, 1)
        val cutoffDate = KnowledgeCutoffDate(date)
        assertTrue(cutoffDate.toString().contains("KnowledgeCutoffDate"), 
            "toString should include class name")
        assertTrue(cutoffDate.toString().contains(cutoffDate.contribution()), 
            "toString should include contribution content")
        
        // Test CurrentDate toString
        val currentDate = CurrentDate()
        assertTrue(currentDate.toString().contains("CurrentDate"), 
            "toString should include class name")
        assertTrue(currentDate.toString().contains("Current date:"), 
            "toString should include contribution content")
        
        // Test FixedPromptContributor toString
        val fixed = PromptContributor.fixed("Test content")
        assertTrue(fixed.toString().contains("FixedPromptContributor"), 
            "toString should include class name")
        assertTrue(fixed.toString().contains("Test content"), 
            "toString should include contribution content")
    }
}