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
             hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(method, logger))
}

/**
 * Checks if a method returning an interface returns a type with a @JsonDeserialize annotation.
 * @param method The Java method to check.
 * @return true if the return type has a @JsonDeserialize annotation, false otherwise.
 */
private fun hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(
    method: Method,
    logger: Logger): Boolean {
    val hasRequiredAnnotation = AnnotationUtils.findAnnotation(method.returnType, JsonDeserialize::class.java) != null ||
            AnnotationUtils.findAnnotation(method.returnType, JsonTypeInfo::class.java) != null
    if (!hasRequiredAnnotation) {
        logger.warn(
            "❓Interface {} used as return type of {}.{} must have @JsonDeserialize or @JsonTypeInfo annotation",
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
