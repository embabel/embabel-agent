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

import java.util.UUID

/**
 * Id of a context.
 */
@JvmInline
value class ContextId(val value: String) {
    init {
        require(value.isNotBlank()) { "ContextId must not be blank" }
    }

    operator fun invoke(id: String) = ContextId(id)

    companion object {
        fun create(): ContextId = ContextId(UUID.randomUUID().toString())
    }
}
