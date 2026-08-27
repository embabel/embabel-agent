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
package com.embabel.agent.spi

/**
 * Marks an [LlmService] that stands in for a model this deployment does not yet have a key for.
 *
 * A placeholder completes nothing. Its only job is to give the platform something to resolve
 * before any key is supplied, so that "no key yet" surfaces as an actionable error at the call
 * that needed an LLM rather than as a failure to start.
 *
 * The platform treats the presence of a placeholder as the deployment's own statement that keys
 * arrive at runtime, and relaxes exactly one thing on the strength of it: model names in
 * configuration that nothing has registered are expected rather than fatal. Every other
 * deployment keeps failing fast on a name it cannot resolve, because there a name that resolves
 * to nothing is a typo.
 *
 * Implemented by `SetupRequiredLlm` in `embabel-agent-byok-autoconfigure`. It lives here, next to
 * [LlmService], because `com.embabel.common.ai.model.ConfigurableModelProvider` has to recognise
 * a placeholder without depending on the BYOK module - carrying the placeholder without dragging
 * in that module is the reason the module exists.
 */
interface PlaceholderLlmService {

    /**
     * A placeholder that reports what was actually wanted, for a role this deployment cannot yet
     * satisfy.
     *
     * In setup-required mode a model name that is merely unregistered and a model name that is
     * misspelled are the same thing at startup - nothing is registered, so neither resolves - and
     * both end up here. The platform cannot tell them apart, but it does know which role asked and
     * which name that role carried, and saying so is what lets the operator tell them apart:
     * `cheapest wanted 'gpt-4.1-nanoo'` points at the typo, where a bare "no LLM is configured"
     * points at the key, the one thing that is not wrong.
     *
     * Returning a distinct instance rather than mutating: one placeholder bean serves every call on
     * any thread, so the role that failed cannot be state on it.
     *
     * @param role the role that could not be satisfied
     * @param model the model name that role named, or null if it named none
     * @return a placeholder failing with a message naming [role] and [model], or null to use this
     * placeholder unchanged - which is what a placeholder with nothing to add should do
     */
    fun forUnsatisfiedRole(role: String, model: String?): LlmService<*>? = null
}
