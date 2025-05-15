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
package com.embabel.agent.config

import com.embabel.agent.spi.support.AgentScanningBeanPostProcessor
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Autowired
//import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile

/**
 *  After all beans got initialized we deploy Agent Platform
 *  Refer to [AgentScanningBeanPostProcessor]
 */
@Profile("!test")
@Component
class PostProcessBeansListener(@Autowired val context: ApplicationContext) :
    ApplicationListener<ContextRefreshedEvent> {


    private val logger = LoggerFactory.getLogger(PostProcessBeansListener::class.java)

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        logger.info("🚀 Application context has been refreshed and all beans are initialized.")

        // get AgentScanningBeanPostProcessor
        val agentScanningBeanPostProcessor = context.getBean(AgentScanningBeanPostProcessor::class.java)

        // Get all beans from the application context
        val allBeans = context.getBeansOfType(Any::class.java)  // Fetch all beans, or filter by your criteria


        // delegate to AgentScanningBeanPostProcessor
        for ((beanName, bean) in allBeans) {
            agentScanningBeanPostProcessor.postProcessAfterInitialization(bean = bean, beanName = beanName)
        }
    }
}
