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

import tools.jackson.databind.annotation.JsonDeserialize
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

    /**
     * Defaults to the ordinary input rate when the provider's cache rate is unknown.
     */
    fun usdPerCacheReadInputToken(): Double = usdPerInputToken()

    /**
     * Defaults to the ordinary input rate when the provider's cache rate is unknown.
     */
    fun usdPerCacheWriteInputToken(): Double = usdPerInputToken()

    /**
     * Long-TTL cache writes (Anthropic 1h at 2x input, OpenAI 12h at 2.5x). When unknown, never
     * cheaper than 2.5x input, the highest such rate, so budgets are not under-counted.
     */
    fun usdPerCacheWriteLongInputToken(): Double = maxOf(usdPerCacheWriteInputToken(), 2.5 * usdPerInputToken())

    /**
     * Prices every token kind a provider adapter reported. Routes through the 4-argument [costOf].
     */
    fun costOf(usage: NativeUsage): Double =
        costOf(usage.inputTokens, usage.outputTokens, usage.cacheReadInputTokens, usage.cacheWriteInputTokens) +
            usdPerCacheWriteLongInputToken() * usage.cacheWriteLongInputTokens

    /**
     * Implementations that override the 2-argument [costOf] must override this one too:
     * this default prices cache tokens at the cache rates outside that override.
     *
     * @param inputTokens input tokens not read from or written to the prompt cache
     */
    fun costOf(inputTokens: Int, outputTokens: Int, cacheReadInputTokens: Int, cacheWriteInputTokens: Int): Double =
        costOf(inputTokens, outputTokens) +
            usdPerCacheReadInputToken() * cacheReadInputTokens +
            usdPerCacheWriteInputToken() * cacheWriteInputTokens

    /**
     * Prices [Usage.getNativeUsage] when it is a [NativeUsage], else the generic counts.
     * @param usage usage whose promptTokens include any cache reads and writes
     */
    fun costOf(usage: Usage): Double {
        (usage.nativeUsage as? NativeUsage)?.let { return costOf(it) }
        val cacheRead = usage.cacheReadInputTokens?.toInt() ?: 0
        val cacheWrite = usage.cacheWriteInputTokens?.toInt() ?: 0
        return costOf(
            inputTokens = (usage.promptTokens.toInt() - cacheRead - cacheWrite).coerceAtLeast(0),
            outputTokens = usage.completionTokens.toInt(),
            cacheReadInputTokens = cacheRead,
            cacheWriteInputTokens = cacheWrite,
        )
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
    val usdPer1mCacheReadInputTokens: Double?,
    val usdPer1mCacheWriteInputTokens: Double?,
    val usdPer1mCacheWriteLongInputTokens: Double?,
) : PricingModel {

    /** Keeps the constructors callers compiled before [usdPer1mCacheWriteLongInputTokens] link to. */
    @JvmOverloads
    constructor(
        usdPer1mInputTokens: Double,
        usdPer1mOutputTokens: Double,
        usdPer1mCacheReadInputTokens: Double? = null,
        usdPer1mCacheWriteInputTokens: Double? = null,
    ) : this(usdPer1mInputTokens, usdPer1mOutputTokens, usdPer1mCacheReadInputTokens, usdPer1mCacheWriteInputTokens, null)

    override fun usdPerInputToken(): Double {
        return usdPer1mInputTokens / ONE_MILLION
    }

    override fun usdPerOutputToken(): Double {
        return usdPer1mOutputTokens / ONE_MILLION
    }

    override fun usdPerCacheReadInputToken(): Double =
        usdPer1mCacheReadInputTokens?.let { it / ONE_MILLION } ?: usdPerInputToken()

    override fun usdPerCacheWriteInputToken(): Double =
        usdPer1mCacheWriteInputTokens?.let { it / ONE_MILLION } ?: usdPerInputToken()

    override fun usdPerCacheWriteLongInputToken(): Double =
        usdPer1mCacheWriteLongInputTokens?.let { it / ONE_MILLION } ?: super.usdPerCacheWriteLongInputToken()
}

/**
 * Uses Anthropic's input-rate read fallback, 1.25x input-rate write fallback and 2x input-rate 1-hour write fallback.
 * 1.25x is Anthropic's 5-minute cache-write rate for current models, not a guarantee for every model:
 * a model priced differently must set [PerTokenPricingModel.usdPer1mCacheWriteInputTokens] and
 * [PerTokenPricingModel.usdPer1mCacheWriteLongInputTokens].
 * Returns this model unchanged when that changes no price.
 */
fun PerTokenPricingModel.withAnthropicCacheRateDefaults(): PerTokenPricingModel =
    if (usdPer1mInputTokens == 0.0 || (usdPer1mCacheWriteInputTokens != null && usdPer1mCacheWriteLongInputTokens != null)) this
    else PerTokenPricingModel(
        usdPer1mInputTokens = usdPer1mInputTokens,
        usdPer1mOutputTokens = usdPer1mOutputTokens,
        usdPer1mCacheReadInputTokens = usdPer1mCacheReadInputTokens,
        usdPer1mCacheWriteInputTokens = usdPer1mCacheWriteInputTokens ?: (usdPer1mInputTokens * 1.25),
        usdPer1mCacheWriteLongInputTokens = usdPer1mCacheWriteLongInputTokens ?: (usdPer1mInputTokens * 2),
    )
