package com.embabel.agent.spi.validation

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Condition
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.slf4j.Logger
import java.lang.reflect.Method

/**
 * Returns true, if the given method is
 *   - annotated with Action and
 *   - declared in the given agent class, or in it's super type.
 *   - TODO - fill with the return type property.
 */
fun isActionMethod(
    logger: Logger,
    method: Method,
    agentClass: Class<*>,
    requireInterfaceDeserializationAnnotations : Boolean,
): Boolean {
    return method.isAnnotationPresent(Action::class.java) &&
            (agentClass.declaredMethods.contains(method) || isMethodFromSupertype(method, agentClass)) &&
            (!method.returnType.isInterface || !requireInterfaceDeserializationAnnotations || hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(
                method,
                logger
            ))
}

/**
 * Returns true, if the given method is  declared in its super type.
 */
fun isMethodFromSupertype(
    method: Method,
    type: Class<*>,
): Boolean {
    // Check interfaces
    if (type.interfaces.any { interfaceType ->
            interfaceType.declaredMethods.any { interfaceMethod ->
                methodSignaturesMatch(method, interfaceMethod)
            }
        }) {
        return true
    }

    // Check superclasses
    var superclass = type.superclass
    while (superclass != null && superclass != Any::class.java) {
        if (superclass.declaredMethods.any { superMethod ->
                methodSignaturesMatch(method, superMethod)
            }) {
            return true
        }
        superclass = superclass.superclass
    }

    return false
}

private fun methodSignaturesMatch(
    method1: Method,
    method2: Method,
): Boolean {
    return method1.name == method2.name &&
            method1.parameterTypes.contentEquals(method2.parameterTypes) &&
            method1.returnType == method2.returnType
}

/**
 * Checks if a method returning an interface returns a type with a @JsonDeserialize annotation.
 * @param method The Java method to check.
 * @return true if the return type has a @JsonDeserialize annotation, false otherwise
 */
private fun hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(method: Method,      logger: Logger): Boolean {
    val hasRequiredAnnotation = method.returnType.isAnnotationPresent(JsonDeserialize::class.java) ||
            method.returnType.isAnnotationPresent(JsonTypeInfo::class.java)
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
 *   - declared in the given agent class, or in it's super type.
 */
fun isConditionMethod(
    method: Method,
    agentClass: Class<*>,
): Boolean {
    return method.isAnnotationPresent(Condition::class.java) &&
            (agentClass.declaredMethods.contains(method) || isMethodFromSupertype(method, agentClass))
}
