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
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.deployment.AgentScanningProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import org.springframework.context.event.ContextRefreshedEvent as ContextRefreshedEvent

/**
 * Event to signal that AgentScanningBeanPostProcessor has completed processing all beans.
 */
class AgentScanningBeanPostProcessorEvent(source: Any) : ApplicationEvent(source)

/**
 * Autoregister beans with @Agent or @Agentic annotations
 */
@Service
@Profile("!test")
internal class AgentScanningPostProcessorDelegate(
    private val agentMetadataReader: AgentMetadataReader,
    private val agentPlatform: AgentPlatform,
    private val properties: AgentScanningProperties,
) {

    private val logger = LoggerFactory.getLogger(AgentScanningPostProcessorDelegate::class.java)

    fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
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

/**
 * Lazy implementation of AgentScanningBeanPostProcessor.
 * Accumulates "other" beans till AgentScanningBeanPostProcessor got fully initialized.
 * <strong>Note:</strong> this is also needed to not short-circuit other PostProcessors
 * for transitive dependencies in the bean graph. (such as ones responsible for Observability, etc.)
 */
@Service
@Profile("!test")
@Order(Ordered.LOWEST_PRECEDENCE)
internal class DelegatingAgentScanningBeanPostProcessor(
    private val applicationContext: ApplicationContext,
    private val applicationEventPublisher: ApplicationEventPublisher,
    ) : BeanPostProcessor,
    ApplicationListener<ContextRefreshedEvent?> {

    private val logger = LoggerFactory.getLogger(DelegatingAgentScanningBeanPostProcessor::class.java)

    private lateinit var agentScanningBeanPostProcessor: AgentScanningPostProcessorDelegate

    // Queue to hold beans that need processing once dependencies are ready
    private val pendingBeans: Queue<BeanProcessingInfo> = ConcurrentLinkedQueue()

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        logger.info("Application context has been refreshed and all beans are initialized.")

        // Now all dependencies are fully initialized -  get AgentScanningBeanPostProcessor
        agentScanningBeanPostProcessor = applicationContext.getBean(AgentScanningPostProcessorDelegate::class.java)

        // Process all accumulated beans
        processPendingBeans()

        // Notify that all beans have been processed
        applicationEventPublisher.publishEvent(
            AgentScanningBeanPostProcessorEvent("Post-processing completed")
        )

        logger.info("All deferred beans got post-processed.")

    }

    @Throws(BeansException::class)
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
        if (this::agentScanningBeanPostProcessor.isInitialized) {
            // Dependencies are ready, process immediately
            return processBean(bean, beanName)
        } else {
            // Dependencies not ready yet, queue for later processing
            pendingBeans.offer(BeanProcessingInfo(bean, beanName))
            return bean // Return original bean
        }
    }

    private fun processPendingBeans() {
        var beanInfo: BeanProcessingInfo
        while ((pendingBeans.poll().also { beanInfo = it }) != null) {
            // Apply the processing that was deferred
            val processedBean = processBean(beanInfo.bean, beanInfo.beanName)
        }
    }

    private fun processBean(bean: Any, beanName: String): Any? {
        // actual processing logic using the dependency by delegation to agentScanningBeanPostProcessor
        return agentScanningBeanPostProcessor.postProcessAfterInitialization(bean = bean, beanName = beanName)
    }

    // Helper class to store bean info
    private data class BeanProcessingInfo(val bean: Any, val beanName: String)

}
