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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [MergedAssetView].
 */
class MergedAssetViewTest {

    @Nested
    inner class MergingTests {

        @Test
        fun `merges assets from multiple views in order`() {
            val asset1 = testAsset("1", "First")
            val asset2 = testAsset("2", "Second")
            val asset3 = testAsset("3", "Third")

            val view1 = AssetView.of(listOf(asset1))
            val view2 = AssetView.of(listOf(asset2, asset3))

            val merged = MergedAssetView(view1, view2)

            assertThat(merged.assets).hasSize(3)
            assertThat(merged.assets.map { it.id }).containsExactly("1", "2", "3")
        }

        @Test
        fun `deduplicates assets by ID keeping first occurrence`() {
            val asset1a = testAsset("1", "First-A")
            val asset1b = testAsset("1", "First-B") // Same ID, different name
            val asset2 = testAsset("2", "Second")

            val view1 = AssetView.of(listOf(asset1a))
            val view2 = AssetView.of(listOf(asset1b, asset2))

            val merged = MergedAssetView(view1, view2)

            assertThat(merged.assets).hasSize(2)
            assertThat(merged.assets.map { it.id }).containsExactly("1", "2")
            // First occurrence (from view1) should be kept
            assertThat((merged.assets[0] as TestAsset).name).isEqualTo("First-A")
        }

        @Test
        fun `handles empty views`() {
            val asset1 = testAsset("1", "First")

            val view1 = AssetView.of(emptyList())
            val view2 = AssetView.of(listOf(asset1))
            val view3 = AssetView.of(emptyList())

            val merged = MergedAssetView(view1, view2, view3)

            assertThat(merged.assets).hasSize(1)
            assertThat(merged.assets[0].id).isEqualTo("1")
        }

        @Test
        fun `handles all empty views`() {
            val view1 = AssetView.of(emptyList())
            val view2 = AssetView.of(emptyList())

            val merged = MergedAssetView(view1, view2)

            assertThat(merged.assets).isEmpty()
        }

        @Test
        fun `handles single view`() {
            val asset1 = testAsset("1", "First")
            val view1 = AssetView.of(listOf(asset1))

            val merged = MergedAssetView(view1)

            assertThat(merged.assets).hasSize(1)
            assertThat(merged.assets[0].id).isEqualTo("1")
        }
    }

    @Nested
    inner class FactoryMethodTests {

        @Test
        fun `of with no views returns empty AssetView`() {
            val result = MergedAssetView.of()

            assertThat(result.assets).isEmpty()
        }

        @Test
        fun `of with single view returns that view directly`() {
            val asset1 = testAsset("1", "First")
            val view1 = AssetView.of(listOf(asset1))

            val result = MergedAssetView.of(view1)

            assertThat(result).isSameAs(view1)
        }

        @Test
        fun `of with multiple views returns MergedAssetView`() {
            val view1 = AssetView.of(listOf(testAsset("1", "First")))
            val view2 = AssetView.of(listOf(testAsset("2", "Second")))

            val result = MergedAssetView.of(view1, view2)

            assertThat(result).isInstanceOf(MergedAssetView::class.java)
            assertThat(result.assets).hasSize(2)
        }

        @Test
        fun `of filters out null views`() {
            val view1 = AssetView.of(listOf(testAsset("1", "First")))

            val result = MergedAssetView.of(null, view1, null)

            assertThat(result).isSameAs(view1)
        }

        @Test
        fun `of with list returns correct view`() {
            val view1 = AssetView.of(listOf(testAsset("1", "First")))
            val view2 = AssetView.of(listOf(testAsset("2", "Second")))

            val result = MergedAssetView.of(listOf(view1, view2))

            assertThat(result.assets).hasSize(2)
            assertThat(result.assets.map { it.id }).containsExactly("1", "2")
        }
    }

    @Nested
    inner class AssetViewMethodsTests {

        @Test
        fun `since filters by timestamp across merged views`() {
            val now = Instant.now()
            val earlier = now.minusSeconds(100)
            val later = now.plusSeconds(100)

            val asset1 = testAsset("1", "Old", earlier)
            val asset2 = testAsset("2", "New", later)

            val view1 = AssetView.of(listOf(asset1))
            val view2 = AssetView.of(listOf(asset2))

            val merged = MergedAssetView(view1, view2)
            val filtered = merged.since(now)

            assertThat(filtered.assets).hasSize(1)
            assertThat(filtered.assets[0].id).isEqualTo("2")
        }

        @Test
        fun `mostRecent returns most recent by timestamp`() {
            val t1 = Instant.now().minusSeconds(300)
            val t2 = Instant.now().minusSeconds(200)
            val t3 = Instant.now().minusSeconds(100)

            val asset1 = testAsset("1", "Oldest", t1)
            val asset2 = testAsset("2", "Middle", t2)
            val asset3 = testAsset("3", "Newest", t3)

            val view1 = AssetView.of(listOf(asset1, asset3))
            val view2 = AssetView.of(listOf(asset2))

            val merged = MergedAssetView(view1, view2)
            val recent = merged.mostRecent(2)

            assertThat(recent.assets).hasSize(2)
            assertThat(recent.assets.map { it.id }).containsExactly("2", "3")
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
