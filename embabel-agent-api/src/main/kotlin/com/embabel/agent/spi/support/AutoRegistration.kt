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
package com.embabel.agent.spi.support

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.AgenticInfo
import com.embabel.agent.api.common.AgentScanningProperties
import com.embabel.agent.core.AgentPlatform
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

/**
 * Autoregister beans with @Agent or @Agentic annotations
 */
@Service
@Profile("!test")
@Order(Ordered.LOWEST_PRECEDENCE)
internal class AgentScanningBeanPostProcessor(
    private val agentMetadataReader: AgentMetadataReader,
    private val agentPlatform: AgentPlatform,
    private val properties: AgentScanningProperties,
) /*: BeanPostProcessor */{

    private val logger = LoggerFactory.getLogger(AgentScanningBeanPostProcessor::class.java)

    /*override */fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
        if (properties.annotation) {
            val agentMetadata = agentMetadataReader.createAgentMetadata(bean)
            if (agentMetadata != null) {
                val agenticInfo = AgenticInfo(bean.javaClass)
                if (!agenticInfo.agentic() || agenticInfo.noAutoScan()) {
                    logger.debug(
                        "Classpath scanning disabled on {}: ignoring this class",
                        bean.javaClass.name,
                    )
                    return null
                }
                agentPlatform.deploy(agentMetadata)
            }
        }
        return bean
    }
}
