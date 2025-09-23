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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.support.expandInputBindings
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import java.lang.reflect.Parameter
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation

/**
 * Strategy interface for resolving action method parameters into argument values and input bindings.
 *
 * @see DefaultActionMethodManager
 */
interface ActionMethodArgumentResolver {

    /**
     * Whether the given method parameter is supported by this resolver.
     * @param javaParameter the Java method parameter to check
     * @param kotlinParameter the Kotlin method parameter to check. Can be `null` if `kotlin-reflect` is unavailable.
     * @param operationContext the current operation context when invoked before [resolveArgument];
     * `null` when invoked before [resolveInputBinding]
     * @return `true` if this resolver supports the supplied parameter; `false` otherwise
     */
    fun supportsParameter(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext?,
    ): Boolean

    /**
     * @param javaParameter the Java method parameter to check. This parameter must have previously been passed to
     * [supportsParameter] which must have returned `true`.
     * @param kotlinParameter the Kotlin method parameter to check. Can be `null` if `kotlin-reflect` is unavailable.
     * @return a set of bindings. Returns an empty set by default.
     */
    fun resolveInputBinding(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
    ): Set<IoBinding> = emptySet()

    /**
     * Resolve an action method parameter into an argument value. An `OperationContext` provides access to the
     * context of the current action.
     * @param javaParameter the Java method parameter to check. This parameter must have previously been passed to
     * [supportsParameter] which must have returned `true`.
     * @param kotlinParameter the Kotlin method parameter to check. Can be `null` if `kotlin-reflect` is unavailable.
     * @param operationContext the current operation context
     */
    fun resolveArgument(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext,
    ): Any?

}

/**
 * Resolves [ProcessContext] arguments.
 */
class ProcessContextArgumentResolver : ActionMethodArgumentResolver {

    override fun supportsParameter(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext?,
    ): Boolean {
        return ProcessContext::class.java.isAssignableFrom(javaParameter.type)
    }

    override fun resolveArgument(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext
    ): Any {
        return operationContext.processContext
    }
}

/**
 * Resolves [OperationContext] arguments.
 */
class OperationContextArgumentResolver : ActionMethodArgumentResolver {

    override fun supportsParameter(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext?,
    ): Boolean {
        return OperationContext::class.java.isAssignableFrom(javaParameter.type)
    }

    override fun resolveArgument(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext
    ): Any {
        return operationContext
    }
}

/**
 * Resolves [Ai] arguments.
 */
class AiArgumentResolver : ActionMethodArgumentResolver {

    override fun supportsParameter(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext?,
    ): Boolean {
        return Ai::class.java.isAssignableFrom(javaParameter.type)
    }

    override fun resolveArgument(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext
    ): Any {
        return operationContext.ai()
    }
}

/**
 * Resolves arguments that can be found on the [com.embabel.agent.core.Blackboard]
 */
class BlackboardArgumentResolver : ActionMethodArgumentResolver {

    override fun supportsParameter(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext?,
    ): Boolean {
        if (kotlinParameter != null) {
            val classifier = kotlinParameter.type.classifier
            if (classifier is KClass<*>) {
                if (operationContext == null) {
                    return true
                }
                val annotation = kotlinParameter.findAnnotation<RequireNameMatch>()
                val name = getBindingParameterName(kotlinParameter.name, annotation)
                    ?: error("Parameter name should be available")
                return operationContext.hasValue(
                    variable = name,
                    type = classifier.java.name,
                    dataDictionary = operationContext.processContext.agentProcess.agent,
                )
            } else {
                return false
            }
        } else if (operationContext != null) {
            val annotation = javaParameter.getAnnotation(RequireNameMatch::class.java)
            val name = getBindingParameterName(javaParameter.name, annotation)
                ?: error("Parameter name should be available")
            return operationContext.hasValue(
                variable = name,
                type = javaParameter.type.name,
                dataDictionary = operationContext.processContext.agentProcess.agent,
            )
        } else {
            return true
        }
    }

    override fun resolveInputBinding(
        javaParameter: Parameter,
        kotlinParameter: KParameter?
    ): Set<IoBinding> {
        if (kotlinParameter != null) {
            if (kotlinParameter.type.isMarkedNullable) {
                return emptySet()
            }
            val annotation = kotlinParameter.findAnnotation<RequireNameMatch>()
            val name = getBindingParameterName(kotlinParameter.name, annotation) ?: throw IllegalArgumentException(
                "Name for argument of type [${kotlinParameter.type}] not specified, and parameter name information not " +
                        "available via reflection. Ensure that the compiler uses the '-parameters' flag."
            )

            return expandInputBindings(
                name,
                (kotlinParameter.type.classifier as KClass<*>).java
            )
        } else {
            val annotation = javaParameter.getAnnotation(RequireNameMatch::class.java)
            val parameterName = if (javaParameter.isNamePresent) javaParameter.name else null
            val name =
                getBindingParameterName(parameterName, annotation) ?: throw IllegalArgumentException(
                    "Name for argument of type [${javaParameter.type}] not specified, and parameter name information not " +
                            "available via reflection. Ensure that the kotlinc compiler uses the '-java-parameters' flag, " +
                            "and that the javac compiler uses the '-parameters' flag."
                )

            return expandInputBindings(
                name,
                javaParameter.type
            )
        }
    }

    override fun resolveArgument(
        javaParameter: Parameter,
        kotlinParameter: KParameter?,
        operationContext: OperationContext
    ): Any? {
        if (kotlinParameter != null) {
            val classifier = kotlinParameter.type.classifier
            if (classifier is KClass<*>) {
                val annotation = kotlinParameter.findAnnotation<RequireNameMatch>()
                val name = getBindingParameterName(kotlinParameter.name, annotation)
                    ?: error("Parameter name should be available")
                val arg = operationContext.getValue(
                    variable = name,
                    type = classifier.java.name,
                    dataDictionary = operationContext.processContext.agentProcess.agent,
                )
                if (arg == null) {
                    val isNullable = kotlinParameter.isOptional || kotlinParameter.type.isMarkedNullable
                    if (!isNullable) {
                        error("Operation ${operationContext.operation.name}: Internal error. No value found in blackboard for non-nullable parameter ${kotlinParameter.name}:${classifier.java.name}")
                    }
                }
                return arg
            }
        }
        val annotation = javaParameter.getAnnotation(RequireNameMatch::class.java)
        val name = getBindingParameterName(javaParameter.name, annotation)
            ?: error("Parameter name should be available")
        return operationContext.getValue(
            variable = name,
            type = javaParameter.type.name,
            dataDictionary = operationContext.processContext.agentProcess.agent,
        )

    }
}
