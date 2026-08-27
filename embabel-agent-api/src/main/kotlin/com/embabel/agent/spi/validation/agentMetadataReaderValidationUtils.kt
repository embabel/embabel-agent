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
package com.embabel.agent.spi.validation

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Condition
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.slf4j.Logger
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Returns true, if the given method is
 *   - annotated with Action and
 *   - declared in the given agent class, or in its super type and
 *   - can be deserialized.
 */
fun isActionMethod(
    logger: Logger,
    method: Method,
    agentClass: Class<*>,
    requireInterfaceDeserializationAnnotations : Boolean,
): Boolean {
    // Check whether given method is annotated with Action.
    return AnnotationUtils.findAnnotation(method, Action::class.java) != null &&
            // Check whether given method is declared in the given agent class, or in its super type.
            ReflectionUtils.findMethod(agentClass, method.name, *method.parameterTypes) != null &&
             // Check whether given method can be deserialized.
            (!method.returnType.isInterface || !requireInterfaceDeserializationAnnotations ||
             isReturnTypeDeSerializable(method, logger))
}

/**
 * Checks if the return type of the method is annotated with @JsonDeserialize or @JsonTypeInfo
 * so that it can be deserialized.
 * @param method The Java method to check.
 * @return true if the return type has @JsonDeserialize/@JsonTypeInfo annotation, false otherwise.
 */
private fun isReturnTypeDeSerializable(
    method: Method,
    logger: Logger): Boolean {
    val hasRequiredAnnotation = AnnotationUtils.findAnnotation(method.returnType, JsonDeserialize::class.java) != null ||
            AnnotationUtils.findAnnotation(method.returnType, JsonTypeInfo::class.java) != null
    if (!hasRequiredAnnotation) {
        logger.warn(
            "Return type {} of {}.{} must have @JsonDeserialize or @JsonTypeInfo annotation so that it can be deserialized.",
            method.returnType.name,
            method.declaringClass.name,
            method.name,
        )
    }
    return hasRequiredAnnotation
}

/**
 * Returns true, if the given method is
 *   - annotated with Condition and
 *   - declared in the given agent class, or in its super type.
 */
fun isConditionMethod(
    method: Method,
    agentClass: Class<*>,
): Boolean {
    return AnnotationUtils.findAnnotation(method, Condition::class.java) != null &&
            (ReflectionUtils.findMethod(agentClass, method.name, *method.parameterTypes) != null)
}
