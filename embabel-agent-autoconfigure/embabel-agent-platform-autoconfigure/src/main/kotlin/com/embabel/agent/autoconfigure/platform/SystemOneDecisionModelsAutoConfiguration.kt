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
package com.embabel.agent.autoconfigure.platform

import com.embabel.agent.config.platform.SystemOneDecisionModelsProperties
import com.embabel.common.ai.decision.systemone.SystemOneClientOptions
import com.embabel.common.ai.decision.systemone.SystemOneModelFactory
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.net.URI
import java.util.function.Supplier

/** Optional SystemOne endpoint using the existing generic decision-service registrar. */
@ApiStatus.Internal
@AutoConfiguration(before = [DecisionModelServicesAutoConfiguration::class])
@ConditionalOnClass(SystemOneModelFactory::class)
@ConditionalOnProperty(prefix = "embabel.agent.platform.models.systemone", name = ["enabled"], havingValue = "true")
@ConditionalOnMissingBean(SystemOneModelFactory::class)
@EnableConfigurationProperties(SystemOneDecisionModelsProperties::class)
class SystemOneDecisionModelsAutoConfiguration {
    @Bean
    fun systemOneModelFactory(properties: SystemOneDecisionModelsProperties, registries: ObjectProvider<ObservationRegistry>): SystemOneModelFactory {
        val uri = try { URI.create(requireNotNull(properties.baseUrl)) }
            catch (_: Exception) { throw IllegalArgumentException("embabel.agent.platform.models.systemone.base-url must specify a valid endpoint") }
        return SystemOneModelFactory(properties.providerId, properties.providerName,
            SystemOneClientOptions(uri, properties.timeout, properties.maxRequestBytes, properties.maxResponseBytes),
            properties.modelNames, registries.getIfUnique { ObservationRegistry.NOOP }, Supplier { properties.apiKey })
    }
}
