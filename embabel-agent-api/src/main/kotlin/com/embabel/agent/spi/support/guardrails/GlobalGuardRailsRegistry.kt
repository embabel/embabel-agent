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
package com.embabel.agent.spi.support.guardrails

import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail
import com.embabel.agent.api.validation.guardrails.GuardRailInstantiationException
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.common.util.loggerFor
import jakarta.annotation.PostConstruct
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils

/**
 * Registry for globally configured guardrails loaded from application properties.
 *
 * Guardrails are instantiated from comma-separated class names specified in:
 * - embabel.agent.guardrails.user-input
 * - embabel.agent.guardrails.assistant-message
 *
 * Global guardrails are created once as singletons and reused across all LLM operations.
 *
 * This registry can be accessed in two ways:
 * - As an injected Spring bean: `constructor(private val registry: GlobalGuardRailsRegistry)`
 * - Statically via companion object: `GlobalGuardRailsRegistry.get()`
 *
 * Example configuration:
 * ```
 * embabel.agent.guardrails.user-input=com.example.ProfanityFilter,com.example.LengthValidator
 * embabel.agent.guardrails.assistant-message=com.example.OutputValidator
 * ```
 */
@Component
class GlobalGuardRailsRegistry(
    @Value("\${embabel.agent.guardrails.user-input:}")
    private val userInputClassNames: String,

    @Value("\${embabel.agent.guardrails.assistant-message:}")
    private val assistantMessageClassNames: String,

    @Value("\${embabel.agent.guardrails.fail-on-error:false}")
    private val failOnError: Boolean
) {
    private val logger = loggerFor<GlobalGuardRailsRegistry>()

    companion object {
        private lateinit var instance: GlobalGuardRailsRegistry
        private lateinit var userInputGuardRails: List<UserInputGuardRail>
        private lateinit var assistantMessageGuardRails: List<AssistantMessageGuardRail>

        /**
         * Get the singleton instance of the registry.
         * Returns null if the registry has not been initialized by Spring.
         */
        fun get(): GlobalGuardRailsRegistry? = if (::instance.isInitialized) instance else null

        /**
         * Get the list of global user input guardrails.
         * Returns empty list if not yet initialized.
         */
        fun getUserInputGuardRails(): List<UserInputGuardRail> =
            if (::userInputGuardRails.isInitialized) userInputGuardRails else emptyList()

        /**
         * Get the list of global assistant message guardrails.
         * Returns empty list if not yet initialized.
         */
        fun getAssistantMessageGuardRails(): List<AssistantMessageGuardRail> =
            if (::assistantMessageGuardRails.isInitialized) assistantMessageGuardRails else emptyList()
    }

    @PostConstruct
    fun init() {
        userInputGuardRails = instantiateGuardRails<UserInputGuardRail>(userInputClassNames)
        assistantMessageGuardRails = instantiateGuardRails<AssistantMessageGuardRail>(assistantMessageClassNames)

        instance = this

        logger.info("GlobalGuardRailsRegistry initialized with {} user-input guardrails, {} assistant-message guardrails",
            userInputGuardRails.size, assistantMessageGuardRails.size)
    }

    private inline fun <reified T> instantiateGuardRails(commaSeparated: String): List<T> {
        if (commaSeparated.isBlank()) return emptyList()

        return commaSeparated.split(",").mapNotNull { className ->
            try {
                val trimmedClassName = className.trim()
                val clazz = ClassUtils.forName(trimmedClassName, ClassUtils.getDefaultClassLoader())

                // Check if class implements the required interface
                if (!ClassUtils.isAssignable(T::class.java, clazz)) {
                    val errorMsg = "Class '$trimmedClassName' does not implement ${T::class.java.simpleName}"
                    logger.error(errorMsg)
                    if (failOnError) {
                        throw GuardRailInstantiationException(errorMsg)
                    }
                    return@mapNotNull null
                }

                val instance = BeanUtils.instantiateClass(clazz) as T

                logger.info("Instantiated guardrail: {}",
                    clazz.simpleName ?: trimmedClassName)

                instance
            } catch (e: Exception) {
                if (e is GuardRailInstantiationException) {
                    throw e
                }
                val errorMsg = "Failed to instantiate guardrail from class '${className.trim()}': ${e.message}"
                logger.error(errorMsg, e)
                if (failOnError) {
                    throw GuardRailInstantiationException(errorMsg, e)
                }
                null
            }
        }
    }
}
