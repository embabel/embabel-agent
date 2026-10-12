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
package com.embabel.agent.autoconfigure.models.clef

import com.embabel.agent.clef.ClefClientOptions
import com.embabel.agent.clef.ClefModelFactory
import com.embabel.agent.config.models.clef.ClefProperties
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

@ApiStatus.Internal
@AutoConfiguration(beforeName = ["com.embabel.agent.autoconfigure.platform.DecisionModelServicesAutoConfiguration"])
@ConditionalOnClass(ClefModelFactory::class)
@ConditionalOnMissingBean(ClefModelFactory::class)
@ConditionalOnProperty(prefix = "embabel.agent.platform.models.clef", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ClefProperties::class)
class AgentClefAutoConfiguration {
    @Bean
    fun clefModelFactory(properties: ClefProperties, registries: ObjectProvider<ObservationRegistry>): ClefModelFactory {
        val uri = try { URI.create(requireNotNull(properties.baseUrl)) }
            catch (_: Exception) { throw IllegalArgumentException("embabel.agent.platform.models.clef.base-url must specify a valid loopback endpoint") }
        return ClefModelFactory(ClefClientOptions(uri, properties.timeout, properties.maxRequestBytes, properties.maxResponseBytes),
            registries.getIfUnique { ObservationRegistry.NOOP })
    }
}
