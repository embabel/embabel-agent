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
package com.embabel.agent.spi.support.decision

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.core.NonRetryable
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.chat.Message
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.LoggerFactory

/**
 * Classifies text and assesses propositions by asking a chat model, retrying failed calls.
 *
 * Every outcome becomes a contract result: a reply that cannot be read or breaks the answer rules
 * is an invalid response, and anything else that goes wrong is unavailability. An interruption during
 * the model call or the wait between retries is the one exception that escapes, as the original
 * [InterruptedException] with the thread's interrupt flag set.
 *
 * @param llm the model to ask, already resolved; the service takes its name and provider from it
 * @param options the options for every call, which should select [llm] directly
 */
internal class LlmDecisionService(
    private val llmOperations: LlmOperations,
    private val llm: LlmService<*>,
    private val options: LlmOptions,
    retry: RetryProperties,
) : DecisionService {

    private val logger = LoggerFactory.getLogger(LlmDecisionService::class.java)

    private val provenance = ModelProvenance(llm.name, llm.provider)

    private val retryTemplate = retry.retryTemplate("decision-${llm.name}")

    override val name: String get() = llm.name

    override val provider: String get() = llm.provider

    override fun classify(request: ClassificationRequest): ClassificationResult =
        decide(CLASSIFY, { ClassificationResult.Failure(it) }) {
            val answer = ask(CLASSIFY, PromptedClassification.messages(request), ClassificationAnswer::class.java)
            PromptedClassification.result(request, answer, provenance)
        }

    override fun assess(request: PropositionRequest): PropositionResult =
        decide(ASSESS, { PropositionResult.Failure(it) }) {
            val answer = ask(ASSESS, PromptedProposition.messages(request), PropositionAnswer::class.java)
            PromptedProposition.result(answer, provenance)
        }

    private fun <A : Any> ask(operation: String, messages: List<Message>, answerType: Class<A>): A =
        retryTemplate.execute<A, Exception> {
            guarded {
                llmOperations.doTransform(
                    messages = messages,
                    interaction = LlmInteraction(
                        id = InteractionId(operation),
                        llm = options,
                        generateExamples = false,
                    ),
                    outputClass = answerType,
                    llmRequestEvent = null,
                )
            }
        }

    /**
     * Runs one decision and maps whatever it throws to a failure result, except an interruption,
     * which it rethrows.
     *
     * The failure keeps only the reason. The exception can carry the model's reply or provider
     * response data, which may echo the input, so it is dropped here and never logged.
     */
    private fun <R : Any> decide(operation: String, failure: (FailureReason) -> R, work: () -> R): R {
        val result = try {
            work()
        } catch (e: DecisionInterrupted) {
            logger.debug("Decision {} with model {} was interrupted", operation, name)
            throw e.interrupted
        } catch (e: Exception) {
            // The retry template reports an interrupted backoff wait as its own exception, with the
            // InterruptedException as the cause.
            interruptionIn(e)?.let { interrupted ->
                Thread.currentThread().interrupt()
                logger.debug("Decision {} with model {} was interrupted", operation, name)
                throw interrupted
            }
            val reason = when (e) {
                is InvalidLlmReturnFormatException, is InvalidDecisionAnswerException -> FailureReason.INVALID_RESPONSE
                else -> FailureReason.UNAVAILABLE
            }
            logger.debug("Decision {} with model {} failed: {}", operation, name, reason)
            return failure(reason)
        }
        logger.debug("Decision {} with model {} returned {}", operation, name, result.javaClass.simpleName)
        return result
    }

    /**
     * Stops the retry template from retrying an interrupted call. The template would otherwise
     * retry it like any other failure and lose the interrupt flag along the way. The interruption
     * can sit anywhere in the cause chain, since the operations often wrap it.
     */
    private inline fun <T> guarded(call: () -> T): T =
        try {
            call()
        } catch (e: Exception) {
            val interrupted = interruptionIn(e) ?: throw e
            Thread.currentThread().interrupt()
            throw DecisionInterrupted(interrupted)
        }

    private fun interruptionIn(e: Throwable): InterruptedException? =
        generateSequence(e) { it.cause }.filterIsInstance<InterruptedException>().firstOrNull()

    /** Carries an interrupted call out of the retry template, which never retries it. */
    private class DecisionInterrupted(val interrupted: InterruptedException) :
        RuntimeException(interrupted), NonRetryable

    private companion object {
        const val CLASSIFY = "classify"
        const val ASSESS = "assess"
    }
}

/**
 * Classification only, backed by a decision service. It reports the classification model family,
 * so it can be registered where a decision service would be the wrong kind.
 */
internal class LlmClassificationService(private val delegate: LlmDecisionService) : ClassificationService {

    override val name: String get() = delegate.name

    override val provider: String get() = delegate.provider

    override fun classify(request: ClassificationRequest): ClassificationResult = delegate.classify(request)
}
