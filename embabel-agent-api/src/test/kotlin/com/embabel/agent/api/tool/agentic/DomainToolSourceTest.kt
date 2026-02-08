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
package com.embabel.agent.api.tool.agentic

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Domain class with @LlmTool methods for testing.
 */
class TestUser(val id: String, val name: String) {
    @LlmTool(description = "Get the user's profile information")
    fun getProfile(): String = "Profile for $name (id: $id)"

    @LlmTool(description = "Update the user's name")
    fun updateName(newName: String): String = "Name updated to $newName"
}

/**
 * Domain class without @LlmTool methods.
 */
class PlainObject(val value: String)

/**
 * Another domain class with @LlmTool methods for testing auto-discovery.
 */
class TestOrder(val orderId: String) {
    @LlmTool(description = "Get order status")
    fun getStatus(): String = "Order $orderId status: pending"
}

class DomainToolSourceTest {

    @Nested
    inner class DomainToolSourceCreation {

        @Test
        fun `should create domain tool source with class`() {
            val source = DomainToolSource(TestUser::class.java)

            assertThat(source.type).isEqualTo(TestUser::class.java)
        }

        @Test
        fun `should create domain tool source with reified type`() {
            val source = DomainToolSource.of<TestUser>()

            assertThat(source.type).isEqualTo(TestUser::class.java)
        }
    }

    @Nested
    inner class DomainToolTrackerTests {

        @Test
        fun `should not bind collections`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val users = listOf(TestUser("1", "Alice"), TestUser("2", "Bob"))

            val tools = tracker.tryBindArtifact(users)

            assertThat(tools).isEmpty()
            assertThat(tracker.hasBoundInstance(TestUser::class.java)).isFalse()
        }

        @Test
        fun `should not bind unregistered types`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val plain = PlainObject("test")

            val tools = tracker.tryBindArtifact(plain)

            assertThat(tools).isEmpty()
        }

        @Test
        fun `should bind single instance of registered type`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val user = TestUser("1", "Alice")

            val tools = tracker.tryBindArtifact(user)

            assertThat(tools).isNotEmpty()
            assertThat(tracker.hasBoundInstance(TestUser::class.java)).isTrue()
            assertThat(tracker.getBoundInstance(TestUser::class.java)).isSameAs(user)
        }

        @Test
        fun `should rebind and replace previous instance (last wins)`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val user1 = TestUser("1", "Alice")
            val user2 = TestUser("2", "Bob")

            tracker.tryBindArtifact(user1)
            val secondBindTools = tracker.tryBindArtifact(user2)

            assertThat(secondBindTools).hasSize(2) // Tools returned for new binding
            assertThat(tracker.getBoundInstance(TestUser::class.java)).isSameAs(user2) // Now second user
        }

        @Test
        fun `should extract tools from bound instance`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val user = TestUser("1", "Alice")

            val tools = tracker.tryBindArtifact(user)

            assertThat(tools).hasSize(2)
            val toolNames = tools.map { it.definition.name }
            assertThat(toolNames).containsExactlyInAnyOrder("getProfile", "updateName")
        }

        @Test
        fun `should not bind when predicate returns false`() {
            // Only bind users with id starting with "admin"
            val source = DomainToolSource(TestUser::class.java) { user, _ ->
                user.id.startsWith("admin")
            }
            val tracker = DomainToolTracker(listOf(source))

            val regularUser = TestUser("user-1", "Alice")
            val tools = tracker.tryBindArtifact(regularUser)

            assertThat(tools).isEmpty()
            assertThat(tracker.hasBoundInstance(TestUser::class.java)).isFalse()
        }

        @Test
        fun `should bind when predicate returns true`() {
            // Only bind users with id starting with "admin"
            val source = DomainToolSource(TestUser::class.java) { user, _ ->
                user.id.startsWith("admin")
            }
            val tracker = DomainToolTracker(listOf(source))

            val adminUser = TestUser("admin-1", "Admin Alice")
            val tools = tracker.tryBindArtifact(adminUser)

            assertThat(tools).hasSize(2)
            assertThat(tracker.hasBoundInstance(TestUser::class.java)).isTrue()
            assertThat(tracker.getBoundInstance(TestUser::class.java)).isSameAs(adminUser)
        }

        @Test
        fun `should replace binding when new artifact passes predicate`() {
            val source = DomainToolSource(TestUser::class.java) { user, _ ->
                user.id.startsWith("admin")
            }
            val tracker = DomainToolTracker(listOf(source))

            val admin1 = TestUser("admin-1", "Admin Alice")
            val regularUser = TestUser("user-1", "Bob")
            val admin2 = TestUser("admin-2", "Admin Charlie")

            tracker.tryBindArtifact(admin1)
            tracker.tryBindArtifact(regularUser) // Should not replace
            assertThat(tracker.getBoundInstance(TestUser::class.java)).isSameAs(admin1)

            tracker.tryBindArtifact(admin2) // Should replace
            assertThat(tracker.getBoundInstance(TestUser::class.java)).isSameAs(admin2)
        }
    }

    @Nested
    inner class DomainBoundToolTests {

        @Test
        fun `should return error when no instance bound`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val placeholderTools = DomainToolFactory.createPlaceholderTools(
                DomainToolSource(TestUser::class.java),
                tracker,
            )

            assertThat(placeholderTools).isNotEmpty()
            val getProfileTool = placeholderTools.find { it.definition.name == "getProfile" }!!

            val result = getProfileTool.call("{}")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).contains("not yet available")
            assertThat(result.content).contains("TestUser")
        }

        @Test
        fun `should execute tool when instance is bound`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val placeholderTools = DomainToolFactory.createPlaceholderTools(
                DomainToolSource(TestUser::class.java),
                tracker,
            )

            // Bind a user
            val user = TestUser("1", "Alice")
            tracker.tryBindArtifact(user)

            val getProfileTool = placeholderTools.find { it.definition.name == "getProfile" }!!
            val result = getProfileTool.call("{}")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).contains("Profile for Alice")
        }

        @Test
        fun `should include availability note in description`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val placeholderTools = DomainToolFactory.createPlaceholderTools(
                DomainToolSource(TestUser::class.java),
                tracker,
            )

            val getProfileTool = placeholderTools.find { it.definition.name == "getProfile" }!!

            assertThat(getProfileTool.definition.description).contains("TestUser")
            assertThat(getProfileTool.definition.description).contains("retrieved first")
        }
    }

    @Nested
    inner class DomainToolFactoryTests {

        @Test
        fun `should create placeholder tools for class with LlmTool methods`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(TestUser::class.java)))
            val tools = DomainToolFactory.createPlaceholderTools(
                DomainToolSource(TestUser::class.java),
                tracker,
            )

            assertThat(tools).hasSize(2)
        }

        @Test
        fun `should return empty list for class without LlmTool methods`() {
            val tracker = DomainToolTracker(listOf(DomainToolSource(PlainObject::class.java)))
            val tools = DomainToolFactory.createPlaceholderTools(
                DomainToolSource(PlainObject::class.java),
                tracker,
            )

            assertThat(tools).isEmpty()
        }
    }

    @Nested
    inner class AutoDiscoveryTests {

        @Test
        fun `should auto-discover tools from any object with LlmTool methods`() {
            val tracker = DomainToolTracker.withAutoDiscovery()

            val user = TestUser("1", "Alice")
            val tools = tracker.tryBindArtifact(user)

            assertThat(tools).hasSize(2)
            val toolNames = tools.map { it.definition.name }
            assertThat(toolNames).containsExactlyInAnyOrder("getProfile", "updateName")
        }

        @Test
        fun `should replace tools when new object is discovered`() {
            val tracker = DomainToolTracker.withAutoDiscovery()

            val user = TestUser("1", "Alice")
            tracker.tryBindArtifact(user)

            val order = TestOrder("order-123")
            val orderTools = tracker.tryBindArtifact(order)

            assertThat(orderTools).hasSize(1)
            assertThat(orderTools[0].definition.name).isEqualTo("getStatus")

            // User tools should no longer be bound
            assertThat(tracker.hasBoundInstance(TestUser::class.java)).isFalse()
            // Order should be bound
            assertThat(tracker.hasBoundInstance(TestOrder::class.java)).isTrue()
        }

        @Test
        fun `should not discover tools from objects without LlmTool methods`() {
            val tracker = DomainToolTracker.withAutoDiscovery()

            val plain = PlainObject("test")
            val tools = tracker.tryBindArtifact(plain)

            assertThat(tools).isEmpty()
        }

        @Test
        fun `should replace with same type (last wins)`() {
            val tracker = DomainToolTracker.withAutoDiscovery()

            val user1 = TestUser("1", "Alice")
            val user2 = TestUser("2", "Bob")

            tracker.tryBindArtifact(user1)
            tracker.tryBindArtifact(user2)

            // Should have second user bound
            val boundUser = tracker.getBoundInstance(TestUser::class.java)
            assertThat(boundUser).isSameAs(user2)
        }
    }
}
