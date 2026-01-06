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

/**
 * Callback interface for interleaving logic with the lifecycle of an AgentProcess and its actions.
 *
 * This is particularly useful for ConcurrentAgentProcess, where launched actions run in their own threads and may,
 * for example, need a Spring Security context proliferated to the thread in which the action runs:
 *
 * ```kotlin
 * @Component
 * @Scope("prototype")
 * class SecurityContextAgentProcessCallback : AgentProcessCallback {
 *     var securityContext: SecurityContext? = null
 *
 *     override fun beforeActionLaunched(process: AgentProcess) {
 *         securityContext = SecurityContextHolder.getContext()
 *     }
 *
 *     override fun onActionLaunched(
 *         process: AgentProcess,
 *         action: Action,
 *     ) {
 *         securityContext?.let {
 *             SecurityContextHolder.setContext(it)
 *         }
 *     }
 *
 *     override fun onActionCompleted(
 *         process: AgentProcess,
 *         action: Action,
 *     ) {
 *         SecurityContextHolder.clearContext()
 *     }
 * }
 * ```
 */
interface AgentProcessCallback {
    fun beforeActionLaunched(process: AgentProcess)
    fun onActionLaunched(process: AgentProcess, action: Action)
    fun onActionCompleted(process: AgentProcess, action: Action)
}
