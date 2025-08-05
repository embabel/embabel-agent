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
package com.embabel.agent.core

import com.embabel.agent.api.common.Aggregation
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.findAllSupertypes
import com.embabel.common.util.loggerFor
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * Allows binding and retrieval of objects using Kotlin
 * operator functions and traditional get/set
 */
interface Bindable {

    /**
     * Bind a value to a name
     */
    operator fun set(
        key: String,
        value: Any,
    ) // don't implement here as it can upset delegation

    fun bind(
        key: String,
        value: Any,
    ): Bindable

    /**
     * Add to entries without binding to a variable name.
     * Implementations must respect the order in which
     * entities were added.
     * This is equivalent to using the default binding name
     * as the key. For example, if you add a Dog to the blackboard
     * without a key, it will be bound to the default
     * binding name "it" and will be the last
     * entry in the list of objects.
     * Equivalent:
     * ```
     * blackboard["it"] = dog
     * blackboard.addObject(dog)
     * ```
     */
    fun addObject(value: Any): Bindable

    fun addAll(objects: List<Any>) {
        objects.forEach { this += it }
    }

    operator fun plusAssign(value: Any)

    operator fun plusAssign(pair: Pair<String, Any>)

    fun bindAll(bindings: Map<String, Any>) =
        bindings.entries.forEach { entry ->
            this[entry.key] = entry.value
        }

    operator fun plusAssign(bindings: Map<String, Any>) {
        bindAll(bindings)
    }
}

interface MayHaveLastResult {

    /**
     * Last result, of any type, if there is one.
     */
    fun lastResult(): Any?

}


/**
 * How agent processes maintain context
 */
interface Blackboard : Bindable, MayHaveLastResult, HasInfoString {

    /**
     * Unique identifier of this blackboard.
     * Blackboard doesn't extend StableIdentified to avoid
     * conflict with implementations that are otherwise identified
     */
    val blackboardId: String

    /**
     * Return the value of a variable, if it is set.
     * Does not limit return via type information.
     */
    operator fun get(name: String): Any?

    /**
     * Resolve the value of a variable, if it is set.
     * Resolve superclasses
     * For example, getValue("it", "Animal") will match a Dog if Dog extends Animal
     */
    @Suppress("UNCHECKED_CAST")
    fun getValue(
        variable: String = IoBinding.DEFAULT_BINDING,
        type: String,
        dataDictionary: DataDictionary,
    ): Any? {
        val bound = this[variable]
        if (bound != null && satisfiesType(bound, type)) {
            return bound
        }

        val aggregationClass = dataDictionary.jvmTypes.map { it.clazz }.filter {
            Aggregation::class.java.isAssignableFrom(it)
        }.find { it.simpleName == type }
        if (aggregationClass != null) {
            val aggregationInstance = aggregationFromBlackboard(
                this,
                aggregationClass.kotlin as KClass<Aggregation>,
            )
            if (aggregationInstance != null) {
                loggerFor<ProcessContext>().info("Adding megazord {} to blackboard", this)
                this += aggregationInstance
            }
        }

        if (variable != IoBinding.DEFAULT_BINDING) {
            // Must be precisely bound
            return null
        }
        return objects.lastOrNull { satisfiesType(boundInstance = it, type) }
    }

    fun <T> count(clazz: Class<T>): Int {
        return objects.filterIsInstance(clazz).size
    }

    /**
     * Last entry of the given type, if there is one
     */
    fun <T> last(clazz: Class<T>): T? {
        return objects.filterIsInstance(clazz).lastOrNull()
    }

    /**
     * Entries in the order they were added.
     * The default instance of any type is the last one
     * Objects are immutable and may not be removed.
     */
    val objects: List<Any>

    /**
     * Spawn an independent child blackboard based on the content of this
     */
    fun spawn(): Blackboard

    /**
     * Explicitly set the condition value
     * Used in planning.
     */
    fun setCondition(
        key: String,
        value: Boolean,
    ): Blackboard

    fun getCondition(key: String): Boolean?

    override fun lastResult(): Any? {
        return objects.lastOrNull()
    }

    /**
     * Expose the model data for use in prompts
     * Prefer more strongly typed usage patterns
     */
    fun expressionEvaluationModel(): Map<String, Any>

}

/**
 * Does the bound instance satisfy the type.
 * Match on simple name or FQN of type or any supertype
 */
fun satisfiesType(
    boundInstance: Any,
    type: String,
): Boolean {
    if (boundInstance::class.simpleName == type) {
        return true
    }
    // Check if the class or any of its superclasses implement the interface
    val interfaces = findAllSupertypes(boundInstance::class.java)
    return interfaces.any { it.simpleName == type || it.name == type }
}

fun <T> Blackboard.all(clazz: Class<T>): List<T> {
    return objects.filterIsInstance(clazz)
}

/**
 * Return all entries of a specific type
 */
inline fun <reified T> Blackboard.all(): List<T> {
    return all(T::class.java)
}

/**
 * Count entries of the given type
 */
inline fun <reified T> Blackboard.count(): Int {
    return count(T::class.java)
}

/**
 * Last entry of the given type, if there is one
 */
inline fun <reified T> Blackboard.last(): T? {
    return last(T::class.java)
}

inline fun <reified T> Blackboard.lastOrNull(predicate: (t: T) -> Boolean): T? {
    return objects.filterIsInstance<T>().lastOrNull { predicate(it) }
}

fun <T> Blackboard.lastOrNull(
    clazz: Class<T>,
    predicate: (t: T) -> Boolean,
): T? {
    return objects.filterIsInstance<T>(clazz).lastOrNull { predicate(it) }
}

/**
 * Try to instantiate an Aggregation subclass from the blackboard
 */
private fun <T : Aggregation> aggregationFromBlackboard(
    blackboard: Blackboard,
    clazz: KClass<T>,
): T? {
    // Get the constructor for the specific Megazord subclass
    val constructor = clazz.constructors.firstOrNull()
        ?: throw IllegalArgumentException("No constructor found for ${clazz.simpleName}")

    // Get the parameters needed for the constructor
    val params = constructor.parameters

    // Map each parameter to its value from the map
    val args = params.associateWith { param ->
        val value = blackboard.last(param.type.toJavaClass())
        if (value == null) {
            loggerFor<Blackboard>().debug("No value found for parameter {} of type {}", param.name, param.type)
            return null
        }
        value
    }
//            if (args.size < params.size) {
//                println("Not all parameters were found in the map")
//                return null
//            }

    // Create a new instance using the constructor and arguments
    return constructor.callBy(args)
}

private fun KType.toJavaClass(): Class<*> {
    val type = this.javaType
    return when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as Class<*>
        else -> throw IllegalArgumentException("Cannot convert KType to Class: $this")
    }
}
