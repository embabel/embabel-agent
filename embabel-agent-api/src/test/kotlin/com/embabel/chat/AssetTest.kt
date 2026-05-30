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
import com.embabel.agent.api.reference.LlmReferenceProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AssetTest {

    @Test
    fun `should create asset from LlmReferenceProvider`() {
        // Arrange
        val mockReference = mock(LlmReference::class.java)
        val provider = mock(LlmReferenceProvider::class.java)
        `when`(provider.reference()).thenReturn(mockReference)

        // Act
        val asset = Asset.asAsset(provider)

        // Assert
        assertNotNull(asset)
        assertNotNull(asset.id)
        assertFalse(asset.persistent())
    }

    @Test
    fun `should generate unique ID for each asset`() {
        // Arrange
        val mockReference1 = mock(LlmReference::class.java)
        val mockReference2 = mock(LlmReference::class.java)
        val provider1 = mock(LlmReferenceProvider::class.java)
        val provider2 = mock(LlmReferenceProvider::class.java)
        `when`(provider1.reference()).thenReturn(mockReference1)
        `when`(provider2.reference()).thenReturn(mockReference2)

        // Act
        val asset1 = Asset.asAsset(provider1)
        val asset2 = Asset.asAsset(provider2)

        // Assert
        assertNotEquals(asset1.id, asset2.id)
    }

    @Test
    fun `should create non-persistent asset by default`() {
        // Arrange
        val mockReference = mock(LlmReference::class.java)
        val provider = mock(LlmReferenceProvider::class.java)
        `when`(provider.reference()).thenReturn(mockReference)

        // Act
        val asset = Asset.asAsset(provider)

        // Assert
        assertFalse(asset.persistent())
    }

    @Test
    fun `should have timestamp on created asset`() {
        // Arrange
        val mockReference = mock(LlmReference::class.java)
        val provider = mock(LlmReferenceProvider::class.java)
        `when`(provider.reference()).thenReturn(mockReference)

        // Act
        val asset = Asset.asAsset(provider)

        // Assert
        assertNotNull(asset.timestamp)
    }
}
