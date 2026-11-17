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
package com.embabel.common.ai.decision

import org.jetbrains.annotations.ApiStatus
import java.time.Duration

/**
 * A typed key for one option a caller can set on a single decision call, such as a timeout or a
 * provider's own tuning knob. Create one with [of].
 *
 * Two keys are the same key when their [id] and [type] match, so a provider and a caller can each
 * build their own copy and still agree.
 *
 * @param T the type of value the option holds
 */
@ApiStatus.Experimental
class DecisionOption<T : Any> private constructor(
    /** The owner of the option, for example `embabel` or a provider's own name. */
    val namespace: String,
    /** The option's name within its namespace. */
    val name: String,
    /** The class every value of this option must be an instance of. */
    val type: Class<T>,
) {

    /** The full key, made of the namespace and the name joined by a dot. */
    val id: String = "$namespace.$name"

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionOption<*> && id == other.id && type == other.type

    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()

    /** Returns the option's [id]. */
    override fun toString(): String = id

    /**
     * Creates option keys.
     */
    companion object {

        /** One or more dot-separated segments, each made of lower-case letters, digits and hyphens. */
        private val DOTTED_SEGMENTS = Regex("[a-z0-9-]+(\\.[a-z0-9-]+)*")

        /**
         * Returns an option key.
         *
         * @param namespace the owner of the option, as one or more dot-separated segments of
         * lower-case letters, digits and hyphens
         * @param name the option's name, in the same form as the namespace
         * @param type the class every value of this option must be an instance of. A primitive
         * class such as `int.class` is stored as its boxed class, so it makes the same key as
         * `Integer.class`
         * @return the option key
         * @throws IllegalArgumentException if the namespace or the name is not in that form
         */
        @JvmStatic
        fun <T : Any> of(namespace: String, name: String, type: Class<T>): DecisionOption<T> {
            require(DOTTED_SEGMENTS.matches(namespace)) {
                "Option namespace '$namespace' must be dot-separated segments of lower-case letters, digits and hyphens"
            }
            require(DOTTED_SEGMENTS.matches(name)) {
                "Option name '$name' must be dot-separated segments of lower-case letters, digits and hyphens"
            }
            // A primitive token such as int.class can never match a value, because values arrive boxed.
            @Suppress("UNCHECKED_CAST")
            return DecisionOption(namespace, name, type.kotlin.javaObjectType as Class<T>)
        }
    }
}

/**
 * Option keys whose meaning is shared by every provider. A service honours one only when its
 * capabilities list the key, for example with `acceptingOption(DecisionOptions.TIMEOUT)`.
 */
@ApiStatus.Experimental
object DecisionOptions {

    /**
     * How long the caller is willing to wait for one decision call, with the id `embabel.timeout`.
     * A service honours it only when its capabilities list it with
     * `acceptingOption(DecisionOptions.TIMEOUT)`. Otherwise a call that sets it is rejected before
     * the provider is called.
     */
    @JvmField
    val TIMEOUT: DecisionOption<Duration> = DecisionOption.of("embabel", "timeout", Duration::class.java)
}
