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
package com.embabel.agent.core

import com.embabel.agent.channel.OutputChannel
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.MulticastAgenticEventListener
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.PlatformServices

/**
 * Process state and services. Created by the platform,
 * not user code.
 */
data class ProcessContext(
    val processOptions: ProcessOptions = ProcessOptions(),
    internal val platformServices: PlatformServices,
    val outputChannel: OutputChannel = platformServices.outputChannel,
    val agentProcess: AgentProcess,
) : LlmOperations by platformServices.llmOperations, AgenticEventListener by MulticastAgenticEventListener(
    processOptions.listeners + platformServices.eventListener,
) {

    val blackboard: Blackboard
        get() = agentProcess

    /**
     * Get a variable value. Handles "it" default type specially,
     * because it could be an "it" of different variables, defined
     * as the most recently added entry.
     */
    fun getValue(
        variable: String,
        type: String,
    ): Any? =
        blackboard.getValue(
            variable = variable, type = type,
            dataDictionary = agentProcess.agent,
        )
}
