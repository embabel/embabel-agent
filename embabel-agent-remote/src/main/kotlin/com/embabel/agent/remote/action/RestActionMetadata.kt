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
package com.embabel.agent.remote.action

import com.embabel.agent.core.IoBinding
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonProperty

data class Io(
    val name: String,
    val type: String,
) {
    fun toIoBinding(): IoBinding = IoBinding(name, type)
}

// TODO improve action interface hierarchy and align with ActionMetadata
/**
 * Data from a remote action
 */
data class RestActionMetadata(
    override val name: String,
    override val description: String,
    val inputs: Set<Io>,
    val outputs: Set<Io>,
    val pre: List<String>,
    val post: List<String>,
    val cost: ZeroToOne,
    val value: ZeroToOne,
    @field:JsonProperty("can_rerun")
    val canRerun: Boolean,
//    val qos: ActionQos,
) : NamedAndDescribed

/**
 * Payload to register a remote server
 * to which this server can delegate actions.
 * Under the baseUrl, the server must expose
 * - /actions to list actions (see RestActionMetadata),
 * - /action/types to list known types (see DynamicType)
 * and /remote/execute to execute actions
 * execute should take action_name and parameters (Map<String, Any>) and return the output (Any)
 * The output must match the dynamic type
 * @param baseUrl URL of the Embabel server below /api/v1
 * @param name name of the server
 * @param description description of the server
 *
 */
data class RestServerRegistration(
    val baseUrl: String,
    override val name: String,
    override val description: String,
) : NamedAndDescribed
