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
package com.embabel.agent.core.hitl

import com.embabel.ux.form.SimpleFormGenerator

/**
 * Call when the current AgentProcess should
 * wait for a response from the user.
 */
fun <P : Any> waitFor(
    awaitable: Awaitable<P, *>,
): P {
    throw AwaitableResponseException(
        awaitable = awaitable,
    )
}

fun <P : Any> confirm(
    what: P,
    description: String,
): P = waitFor(ConfirmationRequest(what, description))

inline fun <reified P : Any> fromForm(
    title: String,
): P = fromForm(
    title = title,
    dataClass = P::class.java,
)

/**
 * Bind input to the data class
 */
fun <P : Any> fromForm(
    title: String? = null,
    dataClass: Class<P>,
): P {
    val form =
        SimpleFormGenerator.generateForm(dataClass = dataClass, title = title ?: "Bind to ${dataClass.simpleName}")
    val formBindingRequest = FormBindingRequest(
        form = form,
        outputClass = dataClass,
        persistent = false,
    )
    throw AwaitableResponseException(
        awaitable = formBindingRequest,
    )
}


/**
 * Not an error, but gets special treatment in the platform.
 */
class AwaitableResponseException(
    val awaitable: Awaitable<*, *>,
) : RuntimeException("Awaitable response exception") {
    override fun toString(): String {
        return "AwaitableResponseException(awaitable=$awaitable)"
    }
}
