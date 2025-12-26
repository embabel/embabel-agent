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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PricingModelsTest {

    @Test
    fun testAllYouCanEat() {
        val pm = PricingModel.ALL_YOU_CAN_EAT
        assertEquals(0.0, pm.usdPerInputToken())
        assertEquals(0.0, pm.usdPerOutputToken())
    }

    @Test
    fun testUsdPerToken() {
        val pm = PricingModel.usdPerToken(1.0, 2.0)
        assertEquals(1.0, pm.usdPerInputToken())
        assertEquals(2.0, pm.usdPerOutputToken())
    }

    @Test
    fun testUsdPer1MTokens() {
        val pm = PricingModel.usdPer1MTokens(1.0, 2.0)
        assertEquals(1.0 / 1000000, pm.usdPerInputToken())
        assertEquals(2.0 / 1000000, pm.usdPerOutputToken())
    }

    @Test
    fun testCostOf() {
        val pm = PricingModel.usdPerToken(1.0, 2.0)
        assertEquals(23.0, pm.costOf(19, 2))
    }

    companion object {
        val UP_TO_16B: PricingModel = PricingModel.usdPer1MTokens(0.20, 0.80)
        val GPT_35_TURBO: PricingModel = PricingModel.usdPerToken(0.001, 0.002)
    }

    @Test
    fun testRatio() {
        val input = 10000
        val output = 50000
        val ratio = GPT_35_TURBO.costOf(input, output) / UP_TO_16B.costOf(input, output)
        assertTrue(ratio > 10)
    }
}
