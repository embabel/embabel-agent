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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.springframework.ai.chat.metadata.Usage

const val ONE_MILLION = 1000000.0

/**
 * Represents a pricing model for an LLM. The models are usually per token pricing,
 * differentiating between input and output tokens, or all you can eat, where there's an
 * hourly rate for the model running whether or not it is in use. See
 * [OpenAI pricing](https://openai.com/pricing#language-models)
 */
@JsonDeserialize(`as` = PerTokenPricingModel::class)
interface PricingModel {

    fun usdPerInputToken(): Double
    fun usdPerOutputToken(): Double

    fun costOf(inputTokens: Int, outputTokens: Int): Double {
        return usdPerInputToken() * inputTokens + usdPerOutputToken() * outputTokens
    }

    fun costOf(usage: Usage): Double {
        return costOf(usage.promptTokens.toInt(), usage.completionTokens.toInt())
    }

    companion object {

        @JvmStatic
        val ALL_YOU_CAN_EAT: PricingModel = PerTokenPricingModel(0.0, 0.0)

        @JvmStatic
        fun usdPerToken(usdPerInputToken: Double, usdPerOutputToken: Double): PricingModel {
            return PerTokenPricingModel(usdPerInputToken * ONE_MILLION, usdPerOutputToken * ONE_MILLION)
        }

        @JvmStatic
        fun usdPer1MTokens(usdPer1mInputTokens: Double, usdPer1mOutputTokens: Double): PricingModel {
            return PerTokenPricingModel(usdPer1mInputTokens, usdPer1mOutputTokens)
        }
    }
}

class PerTokenPricingModel(
    val usdPer1mInputTokens: Double,
    val usdPer1mOutputTokens: Double,
) : PricingModel {

    override fun usdPerInputToken(): Double {
        return usdPer1mInputTokens / ONE_MILLION
    }

    override fun usdPerOutputToken(): Double {
        return usdPer1mOutputTokens / ONE_MILLION
    }
}
