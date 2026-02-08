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

import com.embabel.agent.api.reference.LlmReference
import com.embabel.chat.Asset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryAssetTrackerTest {

    @Test
    fun `starts empty by default`() {
        val tracker = InMemoryAssetTracker()
        assertThat(tracker.assets).isEmpty()
    }

    @Test
    fun `can be initialized with assets`() {
        val asset1 = testAsset("asset-1")
        val asset2 = testAsset("asset-2")

        val tracker = InMemoryAssetTracker(listOf(asset1, asset2))

        assertThat(tracker.assets).hasSize(2)
        assertThat(tracker.assets).contains(asset1, asset2)
    }

    @Test
    fun `addAsset adds asset to tracker`() {
        val tracker = InMemoryAssetTracker()
        val asset = testAsset("test-asset")

        tracker.addAsset(asset)

        assertThat(tracker.assets).hasSize(1)
        assertThat(tracker.assets[0]).isSameAs(asset)
    }

    @Test
    fun `addAsset ignores duplicate asset with same id`() {
        val tracker = InMemoryAssetTracker()
        val asset1 = testAsset("same-id")
        val asset2 = testAsset("same-id")

        tracker.addAsset(asset1)
        tracker.addAsset(asset2)

        assertThat(tracker.assets).hasSize(1)
        assertThat(tracker.assets[0]).isSameAs(asset1)
    }

    @Test
    fun `addAsset allows assets with different ids`() {
        val tracker = InMemoryAssetTracker()
        val asset1 = testAsset("id-1")
        val asset2 = testAsset("id-2")
        val asset3 = testAsset("id-3")

        tracker.addAsset(asset1)
        tracker.addAsset(asset2)
        tracker.addAsset(asset3)

        assertThat(tracker.assets).hasSize(3)
    }

    @Test
    fun `assets returns immutable copy`() {
        val tracker = InMemoryAssetTracker()
        tracker.addAsset(testAsset("asset-1"))

        val assetsBefore = tracker.assets
        tracker.addAsset(testAsset("asset-2"))
        val assetsAfter = tracker.assets

        assertThat(assetsBefore).hasSize(1)
        assertThat(assetsAfter).hasSize(2)
    }

    private fun testAsset(id: String): Asset = object : Asset {
        override val id: String = id
        override val timestamp: Instant = Instant.now()
        override fun reference(): LlmReference = LlmReference.of(
            name = "TestAsset-$id",
            description = "Test asset",
            tools = emptyList(),
        )

        override fun persistent(): Boolean = false
    }
}
