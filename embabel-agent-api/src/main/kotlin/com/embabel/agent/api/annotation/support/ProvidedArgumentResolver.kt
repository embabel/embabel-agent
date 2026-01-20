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

import com.embabel.agent.api.annotation.Provided
import com.embabel.agent.api.common.OperationContext
import java.lang.reflect.Parameter
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation

/**
 * Provides objects from the platform context for injection into action methods.
 *
 * This interface abstracts the mechanism for retrieving platform-managed objects
 * (such as Spring beans) so that the core framework remains runtime-agnostic.
 *
 * @see ProvidedArgumentResolver
 * @see Provided
 */
interface ContextProvider {

    /**
     * Retrieve an object of the specified type from the context.
     *
     * @param type the class of the object to retrieve
     * @return the object instance, or null if not available
     */
    fun <T : Any> getFromContext(type: Class<T>): T?

    /**
     * Check if an object of the specified type is available in the context.
     *
     * @param type the class to check for
     * @return true if an object of this type can be provided
     */
    fun hasInContext(type: Class<*>): Boolean
}

/**
 * Resolves action method parameters annotated with [Provided] from a [ContextProvider].
 *
 * This resolver enables injection of platform-managed objects (such as Spring beans)
 * into action methods, allowing state classes to remain static while still accessing
 * services and components from their enclosing class or the broader application context.
 *
 * @param contextProvider the provider that supplies objects from the platform context
 * @see Provided
 * @see ContextProvider
 */
class ProvidedArgumentResolver(
    private val contextProvider: ContextProvider,
) : ActionMethodArgumentResolver {

    override fun supportsParameter(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext?,
    ): Boolean {
        val hasAnnotation = javaParameter.isAnnotationPresent(Provided::class.java)
            || kotlinParameter?.findAnnotation<Provided>() != null
        if (!hasAnnotation) {
            return false
        }
        return contextProvider.hasInContext(javaParameter.type)
    }

    override fun resolveArgument(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext,
    ): Any? {
        return contextProvider.getFromContext(javaParameter.type)
    }
}
