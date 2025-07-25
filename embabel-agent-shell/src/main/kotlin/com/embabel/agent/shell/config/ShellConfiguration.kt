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
package com.embabel.agent.shell.config

import com.embabel.agent.shell.DefaultPromptProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.shell.jline.PromptProvider

/**
 * Configuration for shell-specific components
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(
    ShellRuntimeHints::class,
    PersonalityResourcesRuntimeHints::class)
@EnableConfigurationProperties(ShellProperties::class)
class ShellConfiguration {

    /**
     * Fallback if we don't have a more interesting prompt provider
     */
    @Bean
    @ConditionalOnMissingBean(PromptProvider::class)
    fun defaultPromptProvider(): PromptProvider = DefaultPromptProvider()
}
