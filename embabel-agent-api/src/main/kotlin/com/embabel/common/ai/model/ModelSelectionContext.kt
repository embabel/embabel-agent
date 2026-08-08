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
package com.embabel.common.ai.model

/**
 * An API key for a named provider, supplied by a user rather than by deployment configuration.
 *
 * @param provider provider name, matching [ModelMetadata.provider] - for example "openai".
 * Compared case-insensitively wherever the platform matches on it.
 * @param apiKey the key itself. Never logged.
 */
data class ProviderCredential(
    val provider: String,
    val apiKey: String,
) {

    /**
     * Deliberately hides the key: these objects end up in log lines and exception messages.
     */
    override fun toString(): String = "ProviderCredential(provider=$provider, apiKey=***)"
}

/**
 * What a [RoleResolver] gets to decide with, beyond the role name itself.
 *
 * A role such as "cheapest" cannot be resolved to a model without knowing which provider is
 * active, and in a bring-your-own-key deployment that is a property of the request rather than
 * of the deployment. Set it for the duration of a request with [ModelSelectionContextHolder.with].
 *
 * @param userId identity of the user on whose behalf the call is made, if any
 * @param credential the provider key active for this call, if any
 */
data class ModelSelectionContext(
    val userId: String? = null,
    val credential: ProviderCredential? = null,
) {

    /**
     * Name of the active provider, or null if the deployment default should be used.
     */
    val provider: String? get() = credential?.provider

    companion object {

        /**
         * No user, no key: resolution falls back to deployment configuration.
         */
        @JvmField
        val EMPTY = ModelSelectionContext()
    }
}

/**
 * Makes the [ModelSelectionContext] for the current call available to model resolution without
 * threading it through every LLM API. Applications set it at their request boundary - a servlet
 * filter, an interceptor, or around the code that starts an agent process.
 *
 * The platform propagates the context across the threads it starts itself - a background agent run,
 * parallel actions, `OperationContext.parallelMap` - because losing it there does not fail loudly:
 * role resolution would quietly fall back to deployment configuration and serve a model the
 * deployment is billed for. It does not reach threads the application spawns on its own. Capture it
 * with [get] and re-establish it with [with] inside such a thread.
 */
object ModelSelectionContextHolder {

    private val current = ThreadLocal.withInitial { ModelSelectionContext.EMPTY }

    /**
     * The context for the current thread, or [ModelSelectionContext.EMPTY] if none was set.
     */
    @JvmStatic
    fun get(): ModelSelectionContext = current.get()

    /**
     * Run [block] with [context] active, restoring the previous context afterwards.
     */
    @JvmStatic
    fun <T> with(context: ModelSelectionContext, block: () -> T): T {
        val previous = current.get()
        current.set(context)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}
