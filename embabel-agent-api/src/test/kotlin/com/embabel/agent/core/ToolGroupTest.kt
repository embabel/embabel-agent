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
package com.embabel.agent.core

import com.embabel.agent.api.tool.Tool
import com.embabel.common.core.types.Semver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for ToolGroup-related classes.
 */
class ToolGroupTest {

    @Nested
    inner class ToolGroupDescriptionTest {

        @Test
        fun `invoke creates ToolGroupDescription with correct properties`() {
            val description = ToolGroupDescription(
                description = "A test tool group",
                role = "test-role",
            )

            assertEquals("A test tool group", description.description)
            assertEquals("test-role", description.role)
        }

        @Test
        fun `create factory method works correctly`() {
            val description = ToolGroupDescription.create(
                description = "Created via factory",
                role = "factory-role",
            )

            assertEquals("Created via factory", description.description)
            assertEquals("factory-role", description.role)
        }
    }

    @Nested
    inner class ToolGroupMetadataTest {

        @Test
        fun `invoke creates ToolGroupMetadata with all properties`() {
            val metadata = ToolGroupMetadata(
                description = "Test description",
                role = "test-role",
                name = "test-tool-group",
                provider = "test-provider",
                permissions = setOf(ToolGroupPermission.INTERNET_ACCESS),
                version = Semver(1, 2, 3),
            )

            assertEquals("Test description", metadata.description)
            assertEquals("test-role", metadata.role)
            assertEquals("test-tool-group", metadata.name)
            assertEquals("test-provider", metadata.provider)
            assertEquals(setOf(ToolGroupPermission.INTERNET_ACCESS), metadata.permissions)
            assertEquals(Semver(1, 2, 3), metadata.version)
        }

        @Test
        fun `invoke with default version creates metadata with default semver`() {
            val metadata = ToolGroupMetadata(
                description = "Test description",
                role = "test-role",
                name = "test-tool-group",
                provider = "test-provider",
                permissions = emptySet(),
            )

            assertEquals(Semver(), metadata.version)
        }

        @Test
        fun `invoke from ToolGroupDescription creates metadata`() {
            val toolGroupDescription = ToolGroupDescription(
                description = "From description",
                role = "desc-role",
            )

            val metadata = ToolGroupMetadata(
                description = toolGroupDescription,
                name = "metadata-name",
                provider = "metadata-provider",
                permissions = setOf(ToolGroupPermission.HOST_ACCESS),
            )

            assertEquals("From description", metadata.description)
            assertEquals("desc-role", metadata.role)
            assertEquals("metadata-name", metadata.name)
            assertEquals("metadata-provider", metadata.provider)
            assertEquals(setOf(ToolGroupPermission.HOST_ACCESS), metadata.permissions)
        }

        @Test
        fun `infoString returns formatted string`() {
            val metadata = ToolGroupMetadata(
                description = "Test description",
                role = "test-role",
                name = "test-name",
                provider = "test-provider",
                permissions = emptySet(),
                version = Semver(1, 0, 0),
            )

            val infoString = metadata.infoString(verbose = false)

            assertTrue(infoString.contains("test-role"))
            assertTrue(infoString.contains("test-name"))
            assertTrue(infoString.contains("test-provider"))
            assertTrue(infoString.contains("Test description"))
        }

        @Test
        fun `metadata with multiple permissions`() {
            val metadata = ToolGroupMetadata(
                description = "Multi-permission group",
                role = "multi-role",
                name = "multi-name",
                provider = "multi-provider",
                permissions = setOf(
                    ToolGroupPermission.HOST_ACCESS,
                    ToolGroupPermission.INTERNET_ACCESS,
                ),
            )

            assertEquals(2, metadata.permissions.size)
            assertTrue(metadata.permissions.contains(ToolGroupPermission.HOST_ACCESS))
            assertTrue(metadata.permissions.contains(ToolGroupPermission.INTERNET_ACCESS))
        }
    }

    @Nested
    inner class ToolGroupRequirementTest {

        @Test
        fun `ToolGroupRequirement holds role correctly`() {
            val requirement = ToolGroupRequirement(role = "required-role")

            assertEquals("required-role", requirement.role)
        }

        @Test
        fun `ToolGroupRequirement equals works correctly`() {
            val req1 = ToolGroupRequirement(role = "same-role")
            val req2 = ToolGroupRequirement(role = "same-role")
            val req3 = ToolGroupRequirement(role = "different-role")

            assertEquals(req1, req2)
            assertNotEquals(req1, req3)
        }
    }

    @Nested
    inner class ToolGroupResolutionTest {

        @Test
        fun `successful resolution has resolved tool group`() {
            val metadata = createTestMetadata("success-role")
            val toolGroup = ToolGroup(metadata, emptyList())

            val resolution = ToolGroupResolution(resolvedToolGroup = toolGroup)

            assertNotNull(resolution.resolvedToolGroup)
            assertNull(resolution.failureMessage)
            assertEquals("success-role", resolution.resolvedToolGroup!!.metadata.role)
        }

        @Test
        fun `failed resolution has failure message`() {
            val resolution = ToolGroupResolution(
                resolvedToolGroup = null,
                failureMessage = "Could not find tool group",
            )

            assertNull(resolution.resolvedToolGroup)
            assertEquals("Could not find tool group", resolution.failureMessage)
        }
    }

    @Nested
    inner class ToolGroupImplementationTest {

        @Test
        fun `invoke creates ToolGroup with tools`() {
            val metadata = createTestMetadata("impl-role")
            val tool = createMockTool("test-tool")

            val toolGroup = ToolGroup(metadata, listOf(tool))

            assertEquals(metadata, toolGroup.metadata)
            assertEquals(1, toolGroup.tools.size)
            assertEquals("test-tool", toolGroup.tools[0].definition.name)
        }

        @Test
        fun `ofTools factory creates ToolGroup with tools`() {
            val metadata = createTestMetadata("factory-role")
            val tool1 = createMockTool("tool-1")
            val tool2 = createMockTool("tool-2")

            val toolGroup = ToolGroup.ofTools(metadata, listOf(tool1, tool2))

            assertEquals(2, toolGroup.tools.size)
        }

        @Test
        fun `default tools returns empty list`() {
            val metadata = createTestMetadata("empty-role")
            val toolGroup = object : ToolGroup {
                override val metadata = metadata
            }

            assertTrue(toolGroup.tools.isEmpty())
        }

        @Test
        fun `infoString verbose true shows tool names`() {
            val metadata = createTestMetadata("info-role")
            val tool1 = createMockTool("alpha-tool")
            val tool2 = createMockTool("beta-tool")
            val toolGroup = ToolGroup(metadata, listOf(tool1, tool2))

            val infoString = toolGroup.infoString(verbose = true)

            assertTrue(infoString.contains("alpha-tool"))
            assertTrue(infoString.contains("beta-tool"))
        }

        @Test
        fun `infoString verbose false shows metadata only`() {
            val metadata = createTestMetadata("info-role")
            val tool = createMockTool("hidden-tool")
            val toolGroup = ToolGroup(metadata, listOf(tool))

            val infoString = toolGroup.infoString(verbose = false)

            assertTrue(infoString.contains("info-role"))
        }

        @Test
        fun `infoString with no tools shows no tools message`() {
            val metadata = createTestMetadata("empty-role")
            val toolGroup = ToolGroup(metadata, emptyList())

            val infoString = toolGroup.infoString(verbose = true)

            assertTrue(infoString.contains("No tools found"))
        }

        @Test
        fun `infoString sorts tool names alphabetically`() {
            val metadata = createTestMetadata("sorted-role")
            val toolC = createMockTool("charlie")
            val toolA = createMockTool("alpha")
            val toolB = createMockTool("bravo")
            val toolGroup = ToolGroup(metadata, listOf(toolC, toolA, toolB))

            val infoString = toolGroup.infoString(verbose = true)

            val alphaIndex = infoString.indexOf("alpha")
            val bravoIndex = infoString.indexOf("bravo")
            val charlieIndex = infoString.indexOf("charlie")

            assertTrue(alphaIndex < bravoIndex)
            assertTrue(bravoIndex < charlieIndex)
        }
    }

    @Nested
    inner class ToolGroupPermissionTest {

        @Test
        fun `HOST_ACCESS permission exists`() {
            val permission = ToolGroupPermission.HOST_ACCESS
            assertEquals("HOST_ACCESS", permission.name)
        }

        @Test
        fun `INTERNET_ACCESS permission exists`() {
            val permission = ToolGroupPermission.INTERNET_ACCESS
            assertEquals("INTERNET_ACCESS", permission.name)
        }

        @Test
        fun `all permissions are distinct`() {
            val permissions = ToolGroupPermission.entries
            assertEquals(2, permissions.size)
            assertEquals(permissions.toSet().size, permissions.size)
        }
    }

    private fun createTestMetadata(role: String): ToolGroupMetadata = ToolGroupMetadata(
        description = "Test tool group for $role",
        role = role,
        name = "test-$role",
        provider = "test-provider",
        permissions = emptySet(),
    )

    private fun createMockTool(name: String): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Mock tool $name",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = Tool.Result.text("{}")
    }
}
