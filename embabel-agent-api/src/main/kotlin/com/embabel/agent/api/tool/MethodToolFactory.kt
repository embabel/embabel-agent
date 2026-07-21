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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.UnfoldingTools
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.cglib.proxy.Enhancer
import org.springframework.core.KotlinDetector
import org.springframework.util.ClassUtils
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation

/**
 * Factory interface for creating tools from annotated methods.
 * Extended by [Tool.Companion] to provide [Tool.fromMethod], [Tool.fromInstance], etc.
 */
interface MethodToolFactory {

    /**
     * Create a Tool from a Kotlin method annotated with [LlmTool].
     *
     * @param instance The object instance containing the method
     * @param method The Kotlin method to wrap as a tool
     * @param objectMapper ObjectMapper for JSON parsing (optional)
     * @return A Tool that invokes the method
     * @throws IllegalArgumentException if the method is not annotated with @LlmTool
     */
    fun fromMethod(
        instance: Any,
        method: KFunction<*>,
        objectMapper: ObjectMapper = jacksonObjectMapper(),
    ): Tool {
        val annotation = method.findAnnotation<LlmTool>()
            ?: throw IllegalArgumentException(
                "Method ${method.name} is not annotated with @LlmTool"
            )

        return KotlinMethodTool(
            instance = instance,
            method = method,
            annotation = annotation,
            objectMapper = objectMapper,
        )
    }

    /**
     * Create a Tool from a Java method annotated with [LlmTool].
     *
     * @param instance The object instance containing the method
     * @param method The Java method to wrap as a tool
     * @param objectMapper ObjectMapper for JSON parsing (optional)
     * @return A Tool that invokes the method
     * @throws IllegalArgumentException if the method is not annotated with @LlmTool
     */
    fun fromMethod(
        instance: Any,
        method: Method,
        objectMapper: ObjectMapper = jacksonObjectMapper(),
    ): Tool {
        val annotation = method.getAnnotation(LlmTool::class.java)
            ?: throw IllegalArgumentException(
                "Method ${method.name} is not annotated with @LlmTool"
            )

        return JavaMethodTool(
            instance = instance,
            method = method,
            annotation = annotation,
            objectMapper = objectMapper,
        )
    }

    /**
     * Create Tools from all methods annotated with [LlmTool] on an instance.
     *
     * If the instance's class is annotated with [@UnfoldingTools][UnfoldingTools]
     * returns a single [UnfoldingTool] containing all the inner tools.
     * Otherwise, returns individual tools for each annotated method.
     *
     * @param instance The object instance to scan for annotated methods
     * @param objectMapper ObjectMapper for JSON parsing (optional)
     * @return List of Tools, one for each annotated method (or single UnfoldingTool if @UnfoldingTools present)
     * @throws IllegalArgumentException if no methods are annotated with @LlmTool
     */
    fun fromInstance(
        instance: Any,
        objectMapper: ObjectMapper = jacksonObjectMapper(),
    ): List<Tool> {
        // A CGLIB proxy's own class never carries the @LlmTool annotations -- they're on the
        // class it subclasses. Reflect over that target class, but still invoke on the proxy
        // instance (below, via fromMethod) so any AOP advice on the proxy actually runs.
        val targetClass: Class<*> = if (Enhancer.isEnhanced(instance.javaClass)) {
            ClassUtils.getUserClass(instance.javaClass)
        } else {
            instance.javaClass
        }

        if (targetClass.kotlin.hasAnnotation<UnfoldingTools>()) {
            return listOf(UnfoldingTool.fromInstance(instance, objectMapper))
        }

        val tools = if (KotlinDetector.isKotlinReflectPresent() && KotlinDetector.isKotlinType(targetClass)) {
            targetClass.kotlin.functions
                .filter { it.hasAnnotation<LlmTool>() }
                .map { fromMethod(instance, it, objectMapper) }
        } else {
            targetClass.declaredMethods
                .filter { it.isAnnotationPresent(LlmTool::class.java) }
                .map { fromMethod(instance, it, objectMapper) }
        }

        if (tools.isEmpty()) {
            throw IllegalArgumentException(
                "No methods annotated with @LlmTool found on ${targetClass.simpleName}"
            )
        }

        return tools
    }

    /**
     * Safely create Tools from an instance, returning empty list if no annotated methods found.
     * This is useful when you want to scan an object that may or may not have tool methods.
     *
     * @param instance The object instance to scan for annotated methods
     * @param objectMapper ObjectMapper for JSON parsing (optional)
     * @return List of Tools, or empty list if no annotated methods found
     */
    fun safelyFromInstance(
        instance: Any,
        objectMapper: ObjectMapper = jacksonObjectMapper(),
    ): List<Tool> {
        return try {
            fromInstance(instance, objectMapper)
        } catch (e: IllegalArgumentException) {
            warnIfLlmToolsHiddenBehindJdkProxy(instance)
            logger.debug("No @LlmTool annotations found on {}: {}", instance::class.simpleName, e.message)
            emptyList()
        } catch (e: Throwable) {
            // Kotlin reflection can fail on some Java classes with KotlinReflectionInternalError
            logger.debug(
                "Failed to scan {} for @LlmTool annotations: {}",
                instance::class.simpleName,
                e.message,
            )
            emptyList()
        }
    }

    /**
     * JDK dynamic proxies can't be unwrapped for discovery -- a method taken from the target
     * class can't be invoked on an interface proxy. So if a JDK proxy's target class does have
     * @LlmTool methods, warn that they're invisible instead of just quietly finding nothing.
     */
    private fun warnIfLlmToolsHiddenBehindJdkProxy(instance: Any) {
        if (!Proxy.isProxyClass(instance.javaClass)) {
            return
        }
        val targetClass = AopProxyUtils.ultimateTargetClass(instance)
        val hasLlmToolMethods = if (KotlinDetector.isKotlinReflectPresent() && KotlinDetector.isKotlinType(targetClass)) {
            targetClass.kotlin.functions.any { it.hasAnnotation<LlmTool>() }
        } else {
            targetClass.declaredMethods.any { it.isAnnotationPresent(LlmTool::class.java) }
        }
        if (hasLlmToolMethods) {
            logger.warn(
                "{} has @LlmTool methods but is only reachable through a JDK dynamic proxy, so they " +
                    "can't be discovered. Use class-based (CGLIB) proxying instead, e.g. spring.aop.proxy-target-class=true.",
                targetClass.name,
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MethodToolFactory::class.java)
    }
}
