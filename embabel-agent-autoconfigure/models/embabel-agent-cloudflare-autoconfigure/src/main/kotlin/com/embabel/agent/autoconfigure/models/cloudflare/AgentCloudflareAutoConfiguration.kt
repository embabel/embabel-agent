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
package com.embabel.agent.autoconfigure.models.cloudflare

import com.embabel.agent.config.models.cloudflare.CloudflareProperties
import com.embabel.common.ai.decision.cloudflare.CloudflareClientOptions
import com.embabel.common.ai.decision.cloudflare.CloudflareModelFactory
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.util.function.Supplier

/** Optional declared Cloudflare catalog; named services use the platform's generic registrar. */
@ApiStatus.Internal
@AutoConfiguration(beforeName = ["com.embabel.agent.autoconfigure.platform.DecisionModelServicesAutoConfiguration"])
@ConditionalOnClass(CloudflareModelFactory::class)
@ConditionalOnProperty(prefix = "embabel.agent.platform.models.cloudflare", name = ["enabled"], havingValue = "true")
@ConditionalOnMissingBean(CloudflareModelFactory::class)
@EnableConfigurationProperties(CloudflareProperties::class)
class AgentCloudflareAutoConfiguration {
    @Bean(destroyMethod = "close")
    fun cloudflareModelFactory(properties: CloudflareProperties, registries: ObjectProvider<ObservationRegistry>): CloudflareModelFactory =
        CloudflareModelFactory(
            CloudflareClientOptions(
                requireNotNull(properties.accountId) { "embabel.agent.platform.models.cloudflare.account-id is required" },
                properties.timeout, properties.maxRequestBytes, properties.maxResponseBytes,
            ),
            properties.modelNames,
            registries.getIfUnique { ObservationRegistry.NOOP },
            Supplier { properties.apiKey },
        )
}
