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
 * the model call or the wait between retries is the one exception that escapes, as an
 * [InterruptedException] with the thread's interrupt flag set. It is the original one when the
 * failure carries it, and otherwise a new one caused by the failure.
 *
 * A failed model call counts as interrupted when an [InterruptedException] is in its cause chain
 * or the thread's interrupt flag is set. Once the model call has returned, the flag no longer matters.
 * An answer that breaks the rules is an invalid response even when the caller's flag was already
 * set, and the service leaves the flag as the caller set it.
 *
 * @param llm the model to ask, already resolved; the service takes its name and provider from it
 * @param options the options for every call, which should select [llm] directly
 * @param retryName the name the retry log lines carry
 */
internal class LlmDecisionService(
    private val llmOperations: LlmOperations,
    private val llm: LlmService<*>,
    private val options: LlmOptions,
    retry: RetryProperties,
    retryName: String = "decision-${llm.name}",
) : DecisionService {

    private val logger = LoggerFactory.getLogger(LlmDecisionService::class.java)

    private val provenance = ModelProvenance(llm.name, llm.provider)

    private val retryTemplate = retry.retryTemplate(retryName)

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
     * An interruption here is an [InterruptedException] in the cause chain. That covers an
     * interrupted model call, which [guarded] has already turned into one, and an interrupted wait
     * between retries. The interrupt flag alone never turns a failure into an interruption here, so
     * an answer that breaks the rules stays an invalid response when the caller's flag was set.
     *
     * The failure keeps only the reason. This class logs only the operation, the model name and
     * the outcome, while the shared model-call path and the retry listener log failed attempts on
     * their own terms.
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
     * retry it like any other failure and lose the interrupt flag along the way.
     *
     * A failed call was interrupted when an [InterruptedException] is in its cause chain, since the
     * operations often wrap it. Without one, a set interrupt flag still means the call was
     * interrupted: an interrupted blocking read can surface as a `ClosedByInterruptException` or
     * another I/O error. Then the interruption is a new [InterruptedException] caused by the
     * failure. The type `InterruptedIOException` proves nothing, because `SocketTimeoutException`
     * extends it and is only a timeout.
     */
    private inline fun <T> guarded(call: () -> T): T =
        try {
            call()
        } catch (e: Exception) {
            val interrupted = interruptionIn(e)
                ?: if (Thread.currentThread().isInterrupted) {
                    InterruptedException("Decision interrupted").apply { initCause(e) }
                } else {
                    throw e
                }
            throw DecisionInterrupted(interrupted)
        }

    /**
     * Finds an [InterruptedException] in the cause chain of a failure, or returns null when there
     * is none. It sets the interrupt flag whenever it finds one.
     */
    private fun interruptionIn(e: Throwable): InterruptedException? =
        generateSequence(e) { it.cause }.filterIsInstance<InterruptedException>().firstOrNull()
            ?.also { Thread.currentThread().interrupt() }

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
// Members are forwarded by hand: interface delegation would forward `type` and report the decision family.
@Suppress("kotlin:S6514")
internal class LlmClassificationService(private val delegate: LlmDecisionService) : ClassificationService {

    override val name: String get() = delegate.name

    override val provider: String get() = delegate.provider

    override fun classify(request: ClassificationRequest): ClassificationResult = delegate.classify(request)
}
