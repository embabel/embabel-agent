package com.embabel.agent.spi.validation

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Condition
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.slf4j.Logger
import org.springframework.util.ClassUtils
import java.lang.reflect.Method

/**
 * Returns true, if the given method is
 *   - annotated with Action and
 *   - declared in the given agent class, or in it's super type.
 *   - TODO -
 */
fun isActionMethod(
    logger: Logger,
    method: Method,
    agentClass: Class<*>,
    requireInterfaceDeserializationAnnotations : Boolean,
): Boolean {
    // Check whether given method is annotated with Action.
    return method.isAnnotationPresent(Action::class.java) &&
            // Check whether given method is declared in the given agent class, or in its super type.
            (agentClass.declaredMethods.contains(method) || isMethodFromSupertype(method, agentClass)) &&
             // TODO please fill after discussion.
            (!method.returnType.isInterface || !requireInterfaceDeserializationAnnotations || hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(
                method,
                logger
            ))
}

/**
 * Returns true, if the given method is declared in its super type.
 */
fun isMethodFromSupertype(
    method: Method,
    type: Class<*>,
): Boolean {
    // Check for the method in its interfaces.
    if (type.interfaces.any { interfaceType ->
            interfaceType.declaredMethods.any { interfaceMethod ->
                methodSignaturesMatch(method, interfaceMethod)
            }
        }) {
        return true
    }

    // Check for the method in its superclasses.
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
    // Finds if method2 matches method1's name and parameter types.
    val match = ClassUtils.getMethodIfAvailable(
        method2.declaringClass,
        method1.name,
        *method1.parameterTypes
    )
    return  match ==  method2 &&
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
 *   - declared in the given agent class, or in its super type.
 */
fun isConditionMethod(
    method: Method,
    agentClass: Class<*>,
): Boolean {
    return method.isAnnotationPresent(Condition::class.java) &&
            (agentClass.declaredMethods.contains(method) || isMethodFromSupertype(method, agentClass))
}
