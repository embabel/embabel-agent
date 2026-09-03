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
package com.embabel.agent.core

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Duration

/**
 * Encapsulates a delay duration for agent operations.
 *
 * Normalizes coarse-grained [Delay] enum values and raw millisecond values
 * into a plain millisecond [Long], so consumers never need to perform their own
 * enum-to-milliseconds lookups.
 *
 * Serialized as a plain millisecond Long value via [millis].
 * A [Duration] view is available via [duration] for callers that require it.
 *
 * Use [DelayPolicy.Inherit] when the action should inherit the enclosing agent's delay.
 * Use [DelayPolicy.of] factory methods to construct from a [Delay] level or raw milliseconds.
 */
sealed interface DelayPolicy {

    @get:JsonValue
    val millis: Long

    val duration: Duration get() = Duration.ofMillis(millis)

    /**
     * Sentinel meaning "not set / inherit from the enclosing agent scope".
     *
     * Serialized as `-1` so it round-trips correctly: `of(-1L)` returns [Inherit],
     * while `of(0L)` returns `Fixed(0)` — explicit zero delay.
     */
    object Inherit : DelayPolicy {
        override val millis: Long = -1L
    }

    /**
     * A fixed delay of [millis] milliseconds.
     */
    data class Fixed(override val millis: Long) : DelayPolicy

    companion object {

        /**
         * Construct a [DelayPolicy] from a coarse-grained [Delay] level.
         * Delegates to [of(Long)]: [Delay.UNSET] (millis = -1) maps to [Inherit];
         * all non-negative values produce [Fixed].
         */
        @JvmStatic
        fun of(delay: Delay): DelayPolicy = of(delay.millis)

        /**
         * Constructs a [DelayPolicy] from an explicit millisecond value.
         * Negative values (including the [@Action][com.embabel.agent.api.annotation.Action]
         * default of `-1`) map to [Inherit] — "not set, inherit from the enclosing scope".
         * Non-negative values produce [Fixed].
         */
        @JsonCreator
        @JvmStatic
        fun of(millis: Long): DelayPolicy = if (millis < 0) Inherit else Fixed(millis)
    }
}
