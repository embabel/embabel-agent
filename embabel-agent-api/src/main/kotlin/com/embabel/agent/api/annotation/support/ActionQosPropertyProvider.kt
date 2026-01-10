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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class ActionQosPropertyProvider(val env: Environment? = null) {

    private val logger = LoggerFactory.getLogger(ActionQosPropertyProvider::class.java)

    private val propertiesBinder: Binder? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        env?.let (Binder::get)
    }

    fun getBound(expr: String): AgentPlatformProperties.ActionQosProperties.ActionProperties? {
        if (expr.isBlank()) return null
        val propertiesBinder = propertiesBinder ?: return null

        val prefix = if (expr.startsWith("\${") && expr.endsWith("}")) {
            expr.substring(2, expr.length - 1).trim()
        } else {
            expr
        }

        val prefixResolved = env.let {
            if (it == null)
                expr
            else
                resolvePrefix(it, prefix) ?: prefix
        }

        return propertiesBinder.bind(prefixResolved, AgentPlatformProperties.ActionQosProperties.ActionProperties::class.java)
            ?.orElse(null)
    }

    private fun resolvePrefix(env: Environment, s: String): String? {
        if (env.containsProperty(s)) {
            return s
        }

        logger.info("Did not find prefix $s when resolving action properties.")
        return null
    }

}
