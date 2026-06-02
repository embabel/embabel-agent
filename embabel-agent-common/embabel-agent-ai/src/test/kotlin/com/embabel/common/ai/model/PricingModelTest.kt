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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage

class PricingModelTest {

    @Test
    fun `should create PerTokenPricingModel with pricing per 1M tokens`() {
        // Arrange & Act
        val model = PerTokenPricingModel(
            usdPer1mInputTokens = 5.0,
            usdPer1mOutputTokens = 15.0
        )

        // Assert
        assertEquals(5.0, model.usdPer1mInputTokens)
        assertEquals(15.0, model.usdPer1mOutputTokens)
    }

    @Test
    fun `should calculate USD per input token correctly`() {
        // Arrange
        val model = PerTokenPricingModel(
            usdPer1mInputTokens = 5.0,
            usdPer1mOutputTokens = 15.0
        )

        // Act
        val usdPerInputToken = model.usdPerInputToken()

        // Assert
        assertEquals(5.0 / ONE_MILLION, usdPerInputToken, 0.00000001)
    }

    @Test
    fun `should calculate USD per output token correctly`() {
        // Arrange
        val model = PerTokenPricingModel(
            usdPer1mInputTokens = 5.0,
            usdPer1mOutputTokens = 15.0
        )

        // Act
        val usdPerOutputToken = model.usdPerOutputToken()

        // Assert
        assertEquals(15.0 / ONE_MILLION, usdPerOutputToken, 0.00000001)
    }

    @Test
    fun `should calculate cost for given input and output tokens`() {
        // Arrange
        val model = PerTokenPricingModel(
            usdPer1mInputTokens = 5.0,
            usdPer1mOutputTokens = 15.0
        )

        // Act
        val cost = model.costOf(100000, 50000)

        // Assert
        val expectedCost = (5.0 / ONE_MILLION) * 100000 + (15.0 / ONE_MILLION) * 50000
        assertEquals(expectedCost, cost, 0.00000001)
    }

    @Test
    fun `should calculate cost from Usage object`() {
        // Arrange
        val model = PerTokenPricingModel(
            usdPer1mInputTokens = 5.0,
            usdPer1mOutputTokens = 15.0
        )
        val usage = DefaultUsage(100000, 50000)

        // Act
        val cost = model.costOf(usage)

        // Assert
        val expectedCost = (5.0 / ONE_MILLION) * 100000 + (15.0 / ONE_MILLION) * 50000
        assertEquals(expectedCost, cost, 0.00000001)
    }

    @Test
    fun `should provide ALL_YOU_CAN_EAT pricing model with zero cost`() {
        // Act
        val model = PricingModel.ALL_YOU_CAN_EAT

        // Assert
        assertEquals(0.0, model.usdPerInputToken())
        assertEquals(0.0, model.usdPerOutputToken())
        assertEquals(0.0, model.costOf(1000000, 1000000))
    }

    @Test
    fun `should create pricing model from usdPerToken`() {
        // Arrange & Act
        val model = PricingModel.usdPerToken(0.000005, 0.000015)

        // Assert
        assertEquals(0.000005, model.usdPerInputToken(), 0.0000000001)
        assertEquals(0.000015, model.usdPerOutputToken(), 0.0000000001)
    }

    @Test
    fun `should create pricing model from usdPer1MTokens`() {
        // Arrange & Act
        val model = PricingModel.usdPer1MTokens(5.0, 15.0)

        // Assert
        assertEquals(5.0 / ONE_MILLION, model.usdPerInputToken(), 0.00000001)
        assertEquals(15.0 / ONE_MILLION, model.usdPerOutputToken(), 0.00000001)
    }

    @Test
    fun `should handle zero token costs`() {
        // Arrange
        val model = PerTokenPricingModel(0.0, 0.0)

        // Act
        val cost = model.costOf(1000000, 1000000)

        // Assert
        assertEquals(0.0, cost)
    }

    @Test
    fun `should handle zero tokens`() {
        // Arrange
        val model = PerTokenPricingModel(5.0, 15.0)

        // Act
        val cost = model.costOf(0, 0)

        // Assert
        assertEquals(0.0, cost)
    }

    @Test
    fun `should calculate realistic OpenAI-like pricing`() {
        // Arrange - GPT-4 Turbo example pricing
        val model = PricingModel.usdPer1MTokens(10.0, 30.0)

        // Act - 1000 input tokens, 500 output tokens
        val cost = model.costOf(1000, 500)

        // Assert
        val expectedCost = 10.0 * 0.001 + 30.0 * 0.0005
        assertEquals(expectedCost, cost, 0.00001)
    }

    @Test
    fun `PerTokenPricingModel should implement PricingModel interface`() {
        // Arrange
        val model = PerTokenPricingModel(5.0, 15.0)

        // Assert
        assertTrue(model is PricingModel)
    }
}
