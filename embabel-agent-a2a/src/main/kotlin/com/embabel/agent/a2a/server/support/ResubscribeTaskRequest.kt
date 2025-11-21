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
package com.embabel.agent.a2a.server.support

import com.fasterxml.jackson.annotation.JsonProperty
import io.a2a.spec.TaskIdParams

/**
 * Request to resubscribe to a task's streaming updates.
 * This allows clients to resume receiving updates for a task after a connection interruption.
 *
 * Corresponds to the A2A protocol method: tasks/resubscribe
 *
 * Note: This is a custom implementation as the A2A SDK version 0.2.5 does not yet
 * include a ResubscribeTaskRequest class. This follows the JSON-RPC 2.0 format
 * and will be compatible with future SDK versions.
 */
data class ResubscribeTaskRequest(
    @JsonProperty("jsonrpc")
    val jsonrpc: String = "2.0",

    @JsonProperty("id")
    val id: Any?,

    @JsonProperty("method")
    val method: String = METHOD,

    @JsonProperty("params")
    val params: TaskIdParams
) {
    companion object {
        const val METHOD = "tasks/resubscribe"
    }
}
