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
package com.embabel.common.util

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Immutable wrapper around the Jackson [tools.jackson.databind.ObjectMapper] used by the Embabel platform.
 *
 * This type is exposed so that the underlying [tools.jackson.databind.ObjectMapper] is not registered directly in the application
 * context. Consumers should call [get] to obtain the configured [tools.jackson.databind.ObjectMapper].
 */
class EmbabelObjectMapperHolder(private val objectMapper: ObjectMapper) {
    fun get(): ObjectMapper = objectMapper

    override fun toString(): String = "EmbabelObjectMapper($objectMapper)"

    companion object {
        @JvmStatic
        fun createDefault(): EmbabelObjectMapperHolder = EmbabelObjectMapperHolder(jacksonObjectMapper())
    }
}
