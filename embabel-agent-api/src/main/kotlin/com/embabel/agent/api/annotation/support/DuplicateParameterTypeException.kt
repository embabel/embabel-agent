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
import java.lang.reflect.Method
import java.lang.reflect.Parameter

/**
 * A class representing a method with multiple parameters of the same type that have not been annotated with
 * [com.embabel.agent.api.annotation.RequireNameMatch].
 */
class DuplicateParameterTypeException(
    val method: Method,
    val duplicates: List<DuplicateParameterType>,
) : RuntimeException(buildDuplicateParameterTypeMessage(method, duplicates))

/**
 * Checks if a method has multiple parameters with the same type, but no @RequireNameMatch annotation.
 * This prevents any issue when the developer forgets to add @RequireNameMatch when parameters have the same type and
 * the system is unable to determine the correct parameter order.
 *
 * @param method The method to check.
 * @throws DuplicateParameterTypeException If the method has multiple parameters with the same type.
 */
fun requireNonAmbiguousParameters(method: Method) {
    val parameterMap: Map<Class<*>, List<Parameter>> = method.parameters.groupBy { it.type }

    // For any class type with more than one parameter, flag as duplicate only when
    // ALL of those parameters are NOT annotated with @RequireNameMatch
    val duplicates = parameterMap
        .asSequence()
        .filter { (_, params) -> params.size > 1 }
        .filter { (_, params) -> isNotProperlyAnnotated(params) }
        .map { (clazz, params) ->
            DuplicateParameterType(
                method = method,
                conflictingClassType = clazz,
                conflictingParameters = params
            )
        }
        .toList()

    if (duplicates.isNotEmpty()) {
        throw DuplicateParameterTypeException(method = method, duplicates)
    }
}

internal fun isNotProperlyAnnotated(params: List<Parameter>): Boolean =
    !params.all { it.isAnnotationPresent(RequireNameMatch::class.java) } &&
            !params.none { it.isAnnotationPresent(RequireNameMatch::class.java) }

/**
 * Builds a detailed error message for the [DuplicateParameterTypeException] that instructs the developer on how to
 * resolve the issue.
 *
 * @param method The method with duplicate parameter types.
 * @param duplicates The list of duplicate parameter types found in the method.
 * @return A formatted error message describing the issue and how to resolve it.
 */
internal fun buildDuplicateParameterTypeMessage(
    method: Method,
    duplicates: List<DuplicateParameterType>,
): String {
    val declaring = method.declaringClass.name
    val params = method.parameters
    fun positionOf(p: Parameter) = params.indexOf(p)
    val details = duplicates.joinToString(separator = "; ") { d ->
        val positions = d.conflictingParameters.joinToString(
            prefix = "(",
            postfix = ")",
            separator = ", "
        ) { p ->
            val idx = positionOf(p)
            val name = p.name
            "#$idx(name='$name')"
        }
        "${d.conflictingClassType.name} at $positions"
    }

    return """
        Ambiguous parameters in $declaring.${method.name}(): multiple parameters share the same type without @RequireNameMatch.
        Conflicts: $details. 
        How to fix: annotate each parameter of the duplicated type with @RequireNameMatch so values are bound by parameter name, or make the parameter types unique.
        """
}
