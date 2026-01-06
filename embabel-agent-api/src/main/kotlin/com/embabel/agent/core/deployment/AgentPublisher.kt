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
package com.embabel.agent.core.deployment

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


/**
 * Automatically deploy all [Agent] beans
 * to the [AgentPlatform].
 */
@Service
class AgentDeployer(
    val agents: List<Agent>,
    private val agentPlatform: AgentPlatform,
    properties: AgentScanningProperties,
) {

    private val logger = LoggerFactory.getLogger(AgentDeployer::class.java)

    init {
        logger.debug("Properties: {}", properties)
        if (!properties.bean) {
            logger.info("AgentDeployer scanning disabled: not looking for agents defined as Spring beans")
        } else {
            logger.info(
                "AgentDeployer scanning enabled: deploying {} agents defined as Spring beans: {}",
                agents.size,
                agents.map { it.name }.sorted().joinToString(", ")
            )

            agents.forEach { agent ->
                agentPlatform.deploy(agent)
            }
        }
    }

}
