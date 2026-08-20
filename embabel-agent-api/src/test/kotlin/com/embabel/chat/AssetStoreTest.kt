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

import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

class AssetStoreTest {

    @Test
    fun `durable asset is persistent and exposes metadata as a reference`() {
        val timestamp = Instant.parse("2026-08-20T00:00:00Z")
        val asset = DurableAsset(
            id = "asset-1",
            name = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42,
            contentHash = "abc123",
            storageUri = "asset://reports/asset-1",
            timestamp = timestamp,
        )

        assertThat(asset.persistent()).isTrue()
        assertThat(asset.timestamp).isEqualTo(timestamp)
        assertThat(asset.reference().name).isEqualTo("report.pdf")
        assertThat(asset.reference().notes()).contains("application/pdf", "42 bytes")
    }

    @Test
    fun `durably returned assets are stored before being tracked`() {
        val source = TestMaterializableAsset()
        val store = RecordingAssetStore()
        val tracker = AssetTracker.inMemory()
        val tool = Tool.of("report", "Create a report") { _ ->
            Tool.Result.withArtifact("created", source)
        }

        tracker.addDurablyReturnedAssets(tool, store).call("{}")

        assertThat(store.stored).containsExactly(source)
        assertThat(tracker.assets).containsExactly(store.durableAsset)
        assertThat(tracker.assets.single().persistent()).isTrue()
    }

    private class TestMaterializableAsset : MaterializableAsset {
        override val id: String = "source-1"
        override val name: String = "report.txt"
        override val mimeType: String = "text/plain"
        override val sizeBytes: Long = 6
        override val timestamp: Instant = Instant.parse("2026-08-20T00:00:00Z")

        override fun persistent(): Boolean = false

        override fun openStream(): InputStream = ByteArrayInputStream("report".toByteArray())

        override fun reference(): LlmReference = LlmReference.of(name, "Test asset", emptyList())
    }

    private class RecordingAssetStore : AssetStore {
        val stored = mutableListOf<MaterializableAsset>()
        val durableAsset = DurableAsset(
            id = "durable-1",
            name = "report.txt",
            mimeType = "text/plain",
            sizeBytes = 6,
            contentHash = "hash",
            storageUri = "asset://durable-1",
        )

        override fun store(asset: MaterializableAsset): DurableAsset {
            stored += asset
            return durableAsset
        }

        override fun open(asset: DurableAsset): InputStream = ByteArrayInputStream("report".toByteArray())

        override fun delete(asset: DurableAsset) = Unit
    }
}
