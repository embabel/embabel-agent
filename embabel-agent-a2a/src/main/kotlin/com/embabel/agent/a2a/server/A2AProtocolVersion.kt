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
package com.embabel.agent.a2a.server

import org.a2aproject.sdk.spec.AgentInterface

/**
 * A2A protocol versions served side by side.
 * The first is the preferred version.
 */
enum class A2AProtocolVersion(val version: String) {

    V1_0(AgentInterface.CURRENT_PROTOCOL_VERSION),

    V0_3("0.3");

    companion object {

        /**
         * Resolves the version a client asked for in the A2A-Version header.
         * Per A2A 1.0, a request without the header is an A2A 0.3 request.
         * Versions with the same major version are compatible, so 1.x is served as 1.0.
         * @return null if the version is not supported
         */
        fun fromHeader(a2aVersion: String?): A2AProtocolVersion? {
            val requested = a2aVersion?.takeIf { it.isNotBlank() }?.trim() ?: return V0_3
            return when {
                requested.substringBefore('.') == V1_0.version.substringBefore('.') -> V1_0
                requested == V0_3.version || requested.startsWith("${V0_3.version}.") -> V0_3
                else -> null
            }
        }
    }
}
