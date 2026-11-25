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

import com.embabel.agent.core.AgentProcess
import com.embabel.common.util.loggerFor
import com.embabel.ux.form.DefaultFormProcessor
import com.embabel.ux.form.Form
import com.embabel.ux.form.FormSubmission
import com.embabel.ux.form.bindTo
import java.time.Instant
import java.util.*

interface ValidationError

/**
 * Present the user with a form
 * and bind it to the given class
 * @param O the class to bind the form submission to
 * @param form the form to present to the user
 * @param outputClass the class to bind the form submission to
 * @param population an optional instance to pre-populate the form
 * @param validationErrors optional validation errors to display on the form
 * @param persistent whether this request should be persisted
 * @param id the original request ID when restoring a saved request
 * @param timestamp the original request timestamp when restoring a saved request
 */
class FormBindingRequest<O : Any>(
    form: Form,
    val outputClass: Class<O>,
    val population: O? = null,
    val validationErrors: List<ValidationError> = emptyList(),
    persistent: Boolean = false,
    val bindingName: String? = null,
    id: String,
    timestamp: Instant,
) : AbstractAwaitable<Form, FormResponse>(
    payload = form,
    id = id,
    timestamp = timestamp,
    persistent = persistent,
) {

    /**
     * Creates a fresh request and preserves the existing Java constructor overloads.
     * The primary constructor requires the saved identity and timestamp when restoring a request.
     */
    @JvmOverloads
    constructor(
        form: Form,
        outputClass: Class<O>,
        population: O? = null,
        validationErrors: List<ValidationError> = emptyList(),
        persistent: Boolean = false,
        bindingName: String? = null,
    ) : this(
        form = form,
        outputClass = outputClass,
        population = population,
        validationErrors = validationErrors,
        persistent = persistent,
        bindingName = bindingName,
        id = UUID.randomUUID().toString(),
        timestamp = Instant.now(),
    )

    private val logger = loggerFor<FormBindingRequest<O>>()

    override fun onResponse(
        response: FormResponse,
        agentProcess: AgentProcess,
    ): ResponseImpact {
        val formSubmissionResult = DefaultFormProcessor().processSubmission(payload, response.formSubmission)
        if (!formSubmissionResult.valid) {
            throw IllegalStateException("Form submission is not valid: ${formSubmissionResult.validationErrors}")
        }
        val boundInstance = formSubmissionResult.bindTo(outputClass)
        return bind(boundInstance, agentProcess)
    }

    fun bind(
        boundInstance: O,
        agentProcess: AgentProcess,
    ): ResponseImpact {
        logger.info("Bound form submission to {}", boundInstance)
        if (bindingName != null) {
            agentProcess[bindingName] = boundInstance
        } else {
            agentProcess += boundInstance
        }
        return ResponseImpact.UPDATED
    }

    override fun toString(): String = infoString(verbose = false)
}

/**
 * Response from the UX
 */
data class FormResponse(
    override val id: String = UUID.randomUUID().toString(),
    override val awaitableId: String,
    val formSubmission: FormSubmission,
    private val persistent: Boolean = false,
    override val timestamp: Instant = Instant.now(),
) : AwaitableResponse {

    override fun persistent() = persistent
}
