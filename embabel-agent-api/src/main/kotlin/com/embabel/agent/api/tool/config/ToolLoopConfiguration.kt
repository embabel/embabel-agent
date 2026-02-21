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
package com.embabel.agent.api.tool.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for tool loop execution.
 *
 * Externalized via properties:
 * ```yaml
 * embabel:
 *   agent:
 *     platform:
 *       toolloop:
 *         type: default              # default | parallel
 *         max-iterations: 20
 *         parallel:
 *           per-tool-timeout: 30s
 *           batch-timeout: 60s
 * ```
 *
 * ## Threading and context propagation
 *
 * Parallel tool execution uses the [com.embabel.agent.api.common.Asyncer] bean,
 * which is the single extension point for controlling threading behavior and
 * propagating execution context (e.g., security context, MDC) to tool execution threads.
 *
 * To customize threading, provide a custom `Asyncer` bean rather than using
 * executor configuration properties.
 *
 * @see com.embabel.agent.api.common.Asyncer
 */
@ConfigurationProperties("embabel.agent.platform.toolloop")
data class ToolLoopConfiguration(
    val type: ToolLoopType = ToolLoopType.DEFAULT,
    val maxIterations: Int = 20,
    val parallel: ParallelModeProperties = ParallelModeProperties(),
) {
    /**
     * Type of tool loop to use.
     */
    enum class ToolLoopType {
        /** Sequential tool execution (default) */
        DEFAULT,
        /** Parallel tool execution (experimental) */
        PARALLEL,
    }

    /**
     * Properties for parallel mode tool execution.
     * Only applicable when [type] is [ToolLoopType.PARALLEL].
     */
    data class ParallelModeProperties(
        /** Timeout for individual tool execution */
        val perToolTimeout: Duration = Duration.ofSeconds(30),
        /** Timeout for entire batch of parallel tools */
        val batchTimeout: Duration = Duration.ofSeconds(60),
        /**
         * Type of executor to use for parallel execution.
         *
         * @deprecated Since 0.3.5 Provide a custom [com.embabel.agent.api.common.Asyncer] bean instead.
         * This property is ignored when an Asyncer bean is available (the default in Spring).
         */
        @Deprecated(
            message = "Provide a custom Asyncer bean instead. This property is ignored when an Asyncer bean is available.",
            level = DeprecationLevel.WARNING,
        )
        val executorType: ExecutorType = ExecutorType.VIRTUAL,
        /**
         * Pool size when using [ExecutorType.FIXED].
         *
         * @deprecated Since 0.3.5. Provide a custom [com.embabel.agent.api.common.Asyncer] bean instead.
         * This property is ignored when an Asyncer bean is available (the default in Spring).
         */
        @Deprecated(
            message = "Provide a custom Asyncer bean instead. This property is ignored when an Asyncer bean is available.",
            level = DeprecationLevel.WARNING,
        )
        val fixedPoolSize: Int = 10,
        /**
         * Timeout for executor shutdown on factory close.
         *
         * @deprecated Since 0.3.5. Provide a custom [com.embabel.agent.api.common.Asyncer] bean instead.
         * This property is ignored when an Asyncer bean is available (the default in Spring).
         */
        @Deprecated(
            message = "Provide a custom Asyncer bean instead. This property is ignored when an Asyncer bean is available.",
            level = DeprecationLevel.WARNING,
        )
        val shutdownTimeout: Duration = Duration.ofSeconds(5),
    )

    /**
     * Type of executor for parallel tool execution.
     *
     * @deprecated Since 0.3.5. Provide a custom [com.embabel.agent.api.common.Asyncer] bean instead.
     */
    @Deprecated(
        message = "Provide a custom Asyncer bean instead.",
        level = DeprecationLevel.WARNING,
    )
    enum class ExecutorType {
        /** Virtual threads (Java 21+, recommended) */
        VIRTUAL,
        /** Fixed thread pool */
        FIXED,
        /** Cached thread pool */
        CACHED,
    }
}
