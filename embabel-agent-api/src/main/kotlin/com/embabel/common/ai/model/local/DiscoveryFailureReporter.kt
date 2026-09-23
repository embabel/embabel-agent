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
package com.embabel.common.ai.model.local

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports a runner's discovery failures once per change, not once per attempt.
 *
 * Discovery used to run once, at startup, so a warning per failure was one warning. Now that a
 * [LocalModelCatalog] asks on every refresh, a runner that is down would warn every few seconds
 * for as long as it stays down. The first failure at an endpoint, and any failure that differs from
 * the last one, is a warning; a repeat is debug; the first success afterwards says it recovered.
 *
 * @param logger the runner configuration's own logger, so the lines keep their existing category
 */
class DiscoveryFailureReporter(
    private val logger: Logger,
) {

    /** The last failure message per endpoint, present only while that endpoint is failing. */
    private val lastFailure = ConcurrentHashMap<String, String>()

    fun failed(endpoint: String, e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        if (lastFailure.put(endpoint, message) != message) {
            logger.warn("Failed to load models from {}: {}", endpoint, message)
        } else {
            logger.debug("Still failing to load models from {}: {}", endpoint, message)
        }
    }

    fun succeeded(endpoint: String) {
        if (lastFailure.remove(endpoint) != null) {
            logger.info("Loading models from {} has recovered", endpoint)
        }
    }
}
