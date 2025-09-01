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

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the agent process repository.
 */
@ConfigurationProperties("embabel.agent.platform.context-repository")
data class ContextRepositoryProperties(
    /**
     * Maximum number of contexts to keep in memory.
     * When this limit is exceeded, the oldest processes will be evicted.
     * Default is 1000.
     */
    val windowSize: Int = 1000,
)
