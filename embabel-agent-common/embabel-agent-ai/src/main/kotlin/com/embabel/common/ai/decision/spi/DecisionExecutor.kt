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
package com.embabel.common.ai.decision.spi

import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.JsonDecisionInput
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException
import org.jetbrains.annotations.ApiStatus

/**
 * The one function a provider writes to answer a whole decision call.
 *
 * Pass it to `DecisionService.from` with the service's metadata and capabilities to get a full
 * decision service. By the time this function runs, the framework has already checked the call
 * against those capabilities, so it only sees question kinds, counts, inputs and options the
 * service said it handles. Whatever it returns is checked against the call's spec.
 */
@ApiStatus.Experimental
fun interface DecisionExecutor {

    /**
     * Answers every question of the call in one provider operation.
     *
     * @param call the checked call, with its input, questions and options
     * @return one answer per question, in spec order
     */
    fun execute(call: DecisionCall): DecisionResponse
}

/**
 * A decision service that answers a [DecisionCall] itself, options and all.
 *
 * `DecisionService.ask(call)` sends the call here when the service, or the hook source of a
 * decorator, implements this interface. A decorator over such a service must implement it too and
 * forward the call.
 */
@ApiStatus.Experimental
fun interface DecisionCallExecution {

    /**
     * Answers the whole call.
     *
     * @param call the call to answer
     * @return one answer per question, in spec order
     * @throws UnsupportedDecisionException if the service cannot answer the call. No provider
     * call has been made.
     */
    fun askCall(call: DecisionCall): DecisionResponse
}

/**
 * Routes a [DecisionCall] to a decision service.
 *
 * A service that answers calls itself gets the call as is. Any other service only understands
 * text requests, so the call is turned into one when nothing would be lost on the way.
 */
@ApiStatus.Internal
object DecisionCalls {

    /**
     * Turns a call into a plain text request for a service that does not answer calls itself.
     *
     * Text goes across unchanged and JSON goes across as its text. A call with options, a call
     * that requires native JSON, or any other input is refused, because the request has no room
     * for them and dropping them quietly would change the answer.
     *
     * @param serviceName the service name used in messages
     * @param call the call to convert
     * @return the equivalent request
     * @throws UnsupportedDecisionException if the call carries anything a request can't hold
     */
    @JvmStatic
    fun toRequest(serviceName: String, call: DecisionCall): DecisionRequest {
        if (call.optionKeys.isNotEmpty()) {
            throw UnsupportedDecisionException(
                "Decision service '$serviceName' does not take call options, but the call sets " +
                    "${call.optionKeys.joinToString { "'${it.id}'" }}. Use a service that supports these options, " +
                    "or ask without them.",
            )
        }
        if (call.nativeJsonRequired) {
            throw UnsupportedDecisionException(
                "Decision service '$serviceName' cannot read JSON natively, and the call requires it. " +
                    "Use a service that accepts JSON input, or let the input be sent as text.",
            )
        }
        val text = when (val input = call.input) {
            is TextDecisionInput -> input.value
            is JsonDecisionInput -> input.toText()
            else -> throw UnsupportedDecisionException(
                "Decision service '$serviceName' only reads text, and the call's input is of kind '${input.kind}'. " +
                    "Use a service that accepts this input kind.",
            )
        }
        return DecisionRequest.of(text, call.spec)
    }

    /**
     * Answers a call on a service, following the routing described on [DecisionCalls].
     *
     * The service whose hooks count is the decorator's hook source when [service] is a
     * [DelegatingDecisionService], and [service] otherwise. When that source answers calls itself,
     * [service] must too, and gets the call. Otherwise the call becomes a request through
     * [toRequest] and goes to `service.ask(request)`.
     *
     * @param service the service to ask
     * @param call the call to answer
     * @return one answer per question, in spec order
     * @throws UnsupportedDecisionException if the service cannot answer the call
     * @throws IllegalStateException if the hook source answers calls itself but the decorator in
     * front of it does not forward them
     */
    @JvmStatic
    fun ask(service: DecisionService, call: DecisionCall): DecisionResponse {
        val source = DecisionExecution.hookSourceOf(service)
        if (source is DecisionCallExecution) {
            check(service is DecisionCallExecution) {
                "Decision service '${service.name}' (${service.javaClass.name}) reports hook source " +
                    "'${source.name}', which implements DecisionCallExecution, but the decorator does not. " +
                    "Implement DecisionCallExecution on ${service.javaClass.name} and forward askCall to the hook source."
            }
            return service.askCall(call)
        }
        return service.ask(toRequest(service.name, call))
    }
}
