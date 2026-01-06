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
package com.embabel.plan.common.condition

/**
 * GOAP and some other planners rely on conditions.
 * Conditions may be true, false or unknown
 */
enum class ConditionDetermination {
    TRUE, FALSE, UNKNOWN;

    /**
     * Treat UNKNOWN as false
     */
    fun asTrueOrFalse(): ConditionDetermination = when (this) {
        TRUE -> TRUE
        else -> FALSE
    }

    companion object {
        operator fun invoke(value: Boolean?) = when (value) {
            true -> TRUE
            false -> FALSE
            null -> UNKNOWN
        }
    }
}

typealias EffectSpec = Map<String, ConditionDetermination>
