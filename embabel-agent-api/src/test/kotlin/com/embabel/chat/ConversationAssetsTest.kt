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
package com.embabel.chat

import com.embabel.agent.api.common.LlmReference
import com.embabel.chat.support.InMemoryAssetTracker
import com.embabel.chat.support.InMemoryConversation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [Conversation.assets] which combines assets from the
 * conversation's AssetTracker and from messages.
 */
class ConversationAssetsTest {

    @Nested
    inner class AssetCombinationTests {

        @Test
        fun `assets includes assets from assetTracker`() {
            val asset = testAsset("tracker-1", "Tracked Asset")
            val tracker = InMemoryAssetTracker(listOf(asset))
            val conversation = InMemoryConversation(assets = tracker)

            assertThat(conversation.assets).hasSize(1)
            assertThat(conversation.assets[0].id).isEqualTo("tracker-1")
        }

        @Test
        fun `assets includes assets from AssistantMessages`() {
            val messageAsset = testAsset("msg-1", "Message Asset")
            val message = AssistantMessage(
                content = "Here's your result",
                assets = listOf(messageAsset)
            )
            val conversation = InMemoryConversation(messages = listOf(message))

            assertThat(conversation.assets).hasSize(1)
            assertThat(conversation.assets[0].id).isEqualTo("msg-1")
        }

        @Test
        fun `assets combines tracker assets and message assets`() {
            val trackerAsset = testAsset("tracker-1", "Tracked Asset")
            val messageAsset = testAsset("msg-1", "Message Asset")

            val tracker = InMemoryAssetTracker(listOf(trackerAsset))
            val message = AssistantMessage(
                content = "Here's your result",
                assets = listOf(messageAsset)
            )
            val conversation = InMemoryConversation(
                messages = listOf(message),
                assets = tracker
            )

            assertThat(conversation.assets).hasSize(2)
            assertThat(conversation.assets.map { it.id }).containsExactly("tracker-1", "msg-1")
        }

        @Test
        fun `tracker assets appear before message assets`() {
            val trackerAsset = testAsset("tracker-1", "Tracked")
            val messageAsset1 = testAsset("msg-1", "Message 1")
            val messageAsset2 = testAsset("msg-2", "Message 2")

            val tracker = InMemoryAssetTracker(listOf(trackerAsset))
            val message1 = AssistantMessage("First", assets = listOf(messageAsset1))
            val message2 = AssistantMessage("Second", assets = listOf(messageAsset2))

            val conversation = InMemoryConversation(
                messages = listOf(message1, message2),
                assets = tracker
            )

            assertThat(conversation.assets).hasSize(3)
            // Tracker first, then messages in order
            assertThat(conversation.assets.map { it.id })
                .containsExactly("tracker-1", "msg-1", "msg-2")
        }

        @Test
        fun `deduplicates assets by ID with tracker taking priority`() {
            val trackerAsset = testAsset("shared-id", "From Tracker")
            val messageAsset = testAsset("shared-id", "From Message") // Same ID

            val tracker = InMemoryAssetTracker(listOf(trackerAsset))
            val message = AssistantMessage("Result", assets = listOf(messageAsset))

            val conversation = InMemoryConversation(
                messages = listOf(message),
                assets = tracker
            )

            assertThat(conversation.assets).hasSize(1)
            assertThat(conversation.assets[0].id).isEqualTo("shared-id")
            // Tracker asset should win (first occurrence)
            assertThat((conversation.assets[0] as TestAsset).name).isEqualTo("From Tracker")
        }
    }

    @Nested
    inner class MessageTypeTests {

        @Test
        fun `UserMessages do not contribute assets`() {
            val userMessage = UserMessage("Hello")
            val conversation = InMemoryConversation(messages = listOf(userMessage))

            assertThat(conversation.assets).isEmpty()
        }

        @Test
        fun `SystemMessages do not contribute assets`() {
            val systemMessage = SystemMessage("You are a helpful assistant")
            val conversation = InMemoryConversation(messages = listOf(systemMessage))

            assertThat(conversation.assets).isEmpty()
        }

        @Test
        fun `only AssistantMessages contribute assets`() {
            val userMessage = UserMessage("Hello")
            val systemMessage = SystemMessage("System prompt")
            val assistantMessage = AssistantMessage(
                content = "Here's a result",
                assets = listOf(testAsset("asset-1", "Result"))
            )

            val conversation = InMemoryConversation(
                messages = listOf(systemMessage, userMessage, assistantMessage)
            )

            assertThat(conversation.assets).hasSize(1)
            assertThat(conversation.assets[0].id).isEqualTo("asset-1")
        }

        @Test
        fun `AssistantMessage with empty assets contributes nothing`() {
            val message = AssistantMessage(content = "No assets here")
            val conversation = InMemoryConversation(messages = listOf(message))

            assertThat(conversation.assets).isEmpty()
        }
    }

    @Nested
    inner class DynamicBehaviorTests {

        @Test
        fun `assets updates when messages are added`() {
            val conversation = InMemoryConversation()

            assertThat(conversation.assets).isEmpty()

            conversation.addMessage(
                AssistantMessage("First", assets = listOf(testAsset("1", "First")))
            )
            assertThat(conversation.assets).hasSize(1)

            conversation.addMessage(
                AssistantMessage("Second", assets = listOf(testAsset("2", "Second")))
            )
            assertThat(conversation.assets).hasSize(2)
        }

        @Test
        fun `assets updates when tracker assets are added`() {
            val tracker = InMemoryAssetTracker()
            val conversation = InMemoryConversation(assets = tracker)

            assertThat(conversation.assets).isEmpty()

            tracker.addAsset(testAsset("1", "First"))
            assertThat(conversation.assets).hasSize(1)

            tracker.addAsset(testAsset("2", "Second"))
            assertThat(conversation.assets).hasSize(2)
        }
    }

    @Nested
    inner class AssetViewMethodsTests {

        @Test
        fun `conversation can use AssetView helper methods`() {
            val earlier = Instant.now().minusSeconds(100)
            val later = Instant.now().plusSeconds(100)

            val oldAsset = testAsset("old", "Old", earlier)
            val newAsset = testAsset("new", "New", later)

            val tracker = InMemoryAssetTracker(listOf(oldAsset))
            val message = AssistantMessage("Result", assets = listOf(newAsset))

            val conversation = InMemoryConversation(
                messages = listOf(message),
                assets = tracker
            )

            // Test since()
            val sinceNow = conversation.since(Instant.now())
            assertThat(sinceNow.assets).hasSize(1)
            assertThat(sinceNow.assets[0].id).isEqualTo("new")

            // Test mostRecent()
            val mostRecent = conversation.mostRecent(1)
            assertThat(mostRecent.assets).hasSize(1)
            assertThat(mostRecent.assets[0].id).isEqualTo("new")

            // Test references()
            val refs = conversation.references()
            assertThat(refs).hasSize(2)
        }
    }

    private fun testAsset(id: String, name: String, timestamp: Instant = Instant.now()): Asset {
        return TestAsset(id, name, timestamp)
    }

    private class TestAsset(
        override val id: String,
        val name: String,
        override val timestamp: Instant,
    ) : Asset {
        override fun persistent(): Boolean = false
        override fun reference(): LlmReference = LlmReference.of(name, "Test asset $name", emptyList())
    }
}
