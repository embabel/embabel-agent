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

import com.embabel.agent.cloudflare.CloudflareModelFactory
import com.embabel.agent.config.models.cloudflare.CloudflareModelsConfig
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Import

/**
 * Registers the Cloudflare CLEF models when the integration is on the classpath. Runs after the
 * shared HTTP transport so the models can reuse the application's HTTP client.
 *
 * An application that supplies its own [CloudflareModelFactory] keeps it, and this configuration
 * then registers nothing and needs no token.
 */
@AutoConfiguration(afterName = ["com.embabel.agent.autoconfigure.netty.NettyClientAutoConfiguration"])
@ConditionalOnClass(CloudflareModelFactory::class)
@ConditionalOnMissingBean(CloudflareModelFactory::class)
@Import(CloudflareModelsConfig::class)
class AgentCloudflareAutoConfiguration
