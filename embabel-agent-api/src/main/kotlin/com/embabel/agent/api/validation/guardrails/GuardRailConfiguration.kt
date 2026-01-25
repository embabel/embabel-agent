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
package com.embabel.agent.api.validation.guardrails

/**
 * Configuration for guardrails in PromptRunner operations.
 */
data class GuardRailConfiguration(
    /**
     * List of direct guardrail instances to apply.
     */
    val guards: List<GuardRail> = emptyList()
) {

    /**
     * Create a new configuration with additional guardrail instances.
     *
     * @param guards the guardrail instances to add
     * @return new configuration with the specified guards added
     */
    fun withGuardRails(vararg guards: GuardRail): GuardRailConfiguration =
        copy(guards = this.guards + guards)

    /**
     * Check if any guardrails are configured.
     */
    fun hasGuards(): Boolean = guards.isNotEmpty()

    companion object {
        /**
         * Empty guardrail configuration with no guards.
         */
        val NONE = GuardRailConfiguration()
    }
}
