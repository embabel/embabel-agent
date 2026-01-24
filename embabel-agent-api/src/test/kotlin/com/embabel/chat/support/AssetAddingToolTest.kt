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
package com.embabel.chat.support

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.support.DelegatingTool
import com.embabel.chat.Asset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class AssetAddingToolTest {

    @Nested
    inner class AssetAddingToolBehavior {

        @Test
        fun `delegates call to underlying tool`() {
            val tracker = InMemoryAssetTracker()
            val delegateTool = Tool.of("test", "Test tool") { _ ->
                Tool.Result.text("delegated result")
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            val result = assetAddingTool.call("{}")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).isEqualTo("delegated result")
        }

        @Test
        fun `preserves tool definition from delegate`() {
            val tracker = InMemoryAssetTracker()
            val delegateTool = Tool.of(
                name = "my_tool",
                description = "My test tool description",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter.string("param", "A parameter")
                )
            ) { _ -> Tool.Result.text("result") }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            assertThat(assetAddingTool.definition.name).isEqualTo("my_tool")
            assertThat(assetAddingTool.definition.description).isEqualTo("My test tool description")
            assertThat(assetAddingTool.definition.inputSchema.parameters).hasSize(1)
        }

        @Test
        fun `implements DelegatingTool interface`() {
            val tracker = InMemoryAssetTracker()
            val delegateTool = Tool.of("test", "Test") { _ -> Tool.Result.text("result") }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            assertThat(assetAddingTool).isInstanceOf(DelegatingTool::class.java)
            assertThat(assetAddingTool.delegate).isSameAs(delegateTool)
        }

        @Test
        fun `adds asset when result has artifact of expected type`() {
            val tracker = InMemoryAssetTracker()
            val testAsset = TestAsset("asset-123")

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", testAsset)
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            assetAddingTool.call("{}")

            assertThat(tracker.assets).hasSize(1)
            assertThat(tracker.assets[0]).isSameAs(testAsset)
        }

        @Test
        fun `converts artifact to asset using converter`() {
            val tracker = InMemoryAssetTracker()

            data class CustomData(val value: String)

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", CustomData("custom-value"))
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { customData: CustomData -> TestAsset("converted-${customData.value}") },
                clazz = CustomData::class.java,
            )

            assetAddingTool.call("{}")

            assertThat(tracker.assets).hasSize(1)
            assertThat(tracker.assets[0].id).isEqualTo("converted-custom-value")
        }

        @Test
        fun `does not add asset when result is Text`() {
            val tracker = InMemoryAssetTracker()
            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.text("just text")
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            val result = assetAddingTool.call("{}")

            assertThat(tracker.assets).isEmpty()
            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
        }

        @Test
        fun `does not add asset when result is Error`() {
            val tracker = InMemoryAssetTracker()
            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.error("Something went wrong")
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            val result = assetAddingTool.call("{}")

            assertThat(tracker.assets).isEmpty()
            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
        }

        @Test
        fun `does not add asset when artifact is wrong type`() {
            val tracker = InMemoryAssetTracker()

            data class WrongType(val value: String)

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", WrongType("wrong"))
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java, // Expecting Asset, but getting WrongType
            )

            val result = assetAddingTool.call("{}")

            assertThat(tracker.assets).isEmpty()
            // Result should still be returned
            assertThat(result).isInstanceOf(Tool.Result.WithArtifact::class.java)
            assertThat((result as Tool.Result.WithArtifact).content).isEqualTo("content")
        }

        @Test
        fun `returns original result even when asset is added`() {
            val tracker = InMemoryAssetTracker()
            val testAsset = TestAsset("asset-456")

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("original content", testAsset)
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            val result = assetAddingTool.call("{}")

            assertThat(result).isInstanceOf(Tool.Result.WithArtifact::class.java)
            assertThat((result as Tool.Result.WithArtifact).content).isEqualTo("original content")
            assertThat(result.artifact).isSameAs(testAsset)
        }

        @Test
        fun `adds all assets when artifact is List of expected type`() {
            val tracker = InMemoryAssetTracker()
            val asset1 = TestAsset("list-asset-1")
            val asset2 = TestAsset("list-asset-2")
            val asset3 = TestAsset("list-asset-3")

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", listOf(asset1, asset2, asset3))
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            assetAddingTool.call("{}")

            assertThat(tracker.assets).hasSize(3)
            assertThat(tracker.assets).containsExactly(asset1, asset2, asset3)
        }

        @Test
        fun `adds all assets when artifact is Set of expected type`() {
            val tracker = InMemoryAssetTracker()
            val asset1 = TestAsset("set-asset-1")
            val asset2 = TestAsset("set-asset-2")

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", setOf(asset1, asset2))
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            assetAddingTool.call("{}")

            assertThat(tracker.assets).hasSize(2)
            assertThat(tracker.assets).contains(asset1, asset2)
        }

        @Test
        fun `filters items in iterable to only expected type`() {
            val tracker = InMemoryAssetTracker()
            val asset1 = TestAsset("typed-asset-1")
            val asset2 = TestAsset("typed-asset-2")

            data class NotAnAsset(val value: String)

            val mixedList = listOf(asset1, NotAnAsset("ignored"), asset2, "also ignored")

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", mixedList)
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            assetAddingTool.call("{}")

            assertThat(tracker.assets).hasSize(2)
            assertThat(tracker.assets).containsExactly(asset1, asset2)
        }

        @Test
        fun `handles empty iterable without error`() {
            val tracker = InMemoryAssetTracker()

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", emptyList<Asset>())
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { it },
                clazz = Asset::class.java,
            )

            val result = assetAddingTool.call("{}")

            assertThat(tracker.assets).isEmpty()
            assertThat(result).isInstanceOf(Tool.Result.WithArtifact::class.java)
        }

        @Test
        fun `converts each item in iterable using converter`() {
            val tracker = InMemoryAssetTracker()

            data class CustomData(val value: String)

            val delegateTool = Tool.of("test", "Test") { _ ->
                Tool.Result.withArtifact("content", listOf(
                    CustomData("item-1"),
                    CustomData("item-2"),
                ))
            }

            val assetAddingTool = AssetAddingTool(
                delegate = delegateTool,
                assetTracker = tracker,
                converter = { data: CustomData -> TestAsset("converted-${data.value}") },
                clazz = CustomData::class.java,
            )

            assetAddingTool.call("{}")

            assertThat(tracker.assets).hasSize(2)
            assertThat(tracker.assets.map { it.id }).containsExactly("converted-item-1", "converted-item-2")
        }
    }

    @Nested
    inner class AssetTrackerIntegration {

        @Test
        fun `trackAnyAsset wraps tool correctly`() {
            val tracker = InMemoryAssetTracker()
            val originalTool = Tool.of("original", "Original tool") { _ ->
                Tool.Result.text("result")
            }

            val wrappedTool = tracker.addReturnedAssets(originalTool)

            assertThat(wrappedTool).isInstanceOf(AssetAddingTool::class.java)
            assertThat((wrappedTool as DelegatingTool).delegate).isSameAs(originalTool)
            assertThat(wrappedTool.definition.name).isEqualTo("original")
        }

        @Test
        fun `trackAnyAsset adds Asset artifacts automatically`() {
            val tracker = InMemoryAssetTracker()
            val testAsset = TestAsset("tracked-asset")

            val originalTool = Tool.of("producer", "Produces assets") { _ ->
                Tool.Result.withArtifact("produced", testAsset)
            }

            val wrappedTool = tracker.addReturnedAssets(originalTool)
            wrappedTool.call("{}")

            assertThat(tracker.assets).hasSize(1)
            assertThat(tracker.assets[0]).isSameAs(testAsset)
        }

        @Test
        fun `trackAnyAssets wraps multiple tools`() {
            val tracker = InMemoryAssetTracker()
            val tool1 = Tool.of("tool1", "First tool") { _ -> Tool.Result.text("1") }
            val tool2 = Tool.of("tool2", "Second tool") { _ -> Tool.Result.text("2") }
            val tool3 = Tool.of("tool3", "Third tool") { _ -> Tool.Result.text("3") }

            val wrappedTools = tracker.addAnyReturnedAssets(listOf(tool1, tool2, tool3))

            assertThat(wrappedTools).hasSize(3)
            assertThat(wrappedTools).allMatch { it is AssetAddingTool<*> }
            assertThat(wrappedTools.map { it.definition.name }).containsExactly("tool1", "tool2", "tool3")
        }

        @Test
        fun `trackAnyAssets returns empty list for empty input`() {
            val tracker = InMemoryAssetTracker()

            val wrappedTools = tracker.addAnyReturnedAssets(emptyList())

            assertThat(wrappedTools).isEmpty()
        }

        @Test
        fun `multiple wrapped tools can add to same tracker`() {
            val tracker = InMemoryAssetTracker()
            val asset1 = TestAsset("asset-1")
            val asset2 = TestAsset("asset-2")

            val tool1 = Tool.of("tool1", "First") { _ -> Tool.Result.withArtifact("1", asset1) }
            val tool2 = Tool.of("tool2", "Second") { _ -> Tool.Result.withArtifact("2", asset2) }

            val wrappedTools = tracker.addAnyReturnedAssets(listOf(tool1, tool2))

            wrappedTools[0].call("{}")
            wrappedTools[1].call("{}")

            assertThat(tracker.assets).hasSize(2)
            assertThat(tracker.assets).containsExactly(asset1, asset2)
        }

        @Test
        fun `trackAnyAsset ignores non-Asset artifacts`() {
            val tracker = InMemoryAssetTracker()

            data class NotAnAsset(val data: String)

            val originalTool = Tool.of("producer", "Produces non-assets") { _ ->
                Tool.Result.withArtifact("produced", NotAnAsset("not-an-asset"))
            }

            val wrappedTool = tracker.addReturnedAssets(originalTool)
            val result = wrappedTool.call("{}")

            assertThat(tracker.assets).isEmpty()
            // Result should still be returned with artifact
            assertThat(result).isInstanceOf(Tool.Result.WithArtifact::class.java)
        }
    }
}

// Test implementations

private class TestAsset(
    override val id: String,
    override val timestamp: Instant = Instant.now(),
) : Asset {
    override fun reference(): LlmReference = LlmReference.of(
        name = "TestAsset-$id",
        description = "Test asset",
        tools = emptyList(),
    )

    override fun persistent(): Boolean {
        return false
    }
}
