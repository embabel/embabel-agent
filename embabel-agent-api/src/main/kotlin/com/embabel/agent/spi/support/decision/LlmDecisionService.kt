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
import com.embabel.agent.spi.common.LlmRetryDecision
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.chat.Message
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * Answers decision questions by asking a chat model, retrying failed calls.
 *
 * The service classifies text, assesses propositions, and answers a whole decision request of
 * proposition, choice and rating questions in one model call through [askNative]. A single choice
 * question goes through the classification prompt with the question's instructions and categories.
 * A single rating question goes through the same prompt as a one-question request. A single
 * proposition question goes through the proposition prompt with the question's instructions. The model reports
 * verdicts and ids only, so no outcome carries a confidence, distribution or score.
 *
 * A question set reply is read after the retry template returns, so a reply that cannot be read
 * makes one model call and is never retried.
 *
 * Every outcome becomes a contract result: a reply that cannot be read or breaks the answer rules
 * is an invalid response, and anything else that goes wrong is unavailability. An interruption during
 * the model call or the wait between retries is the one exception that escapes, as an unchecked
 * [CancellationException] with the thread's interrupt flag set. Its cause is the interruption: the
 * original [InterruptedException] when the failure carries one, and otherwise a new one caused by
 * the failure.
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
) : DecisionService, NativeQuestionSetExecution, PropositionAssessment, RatingAssessment {

    private val logger = LoggerFactory.getLogger(LlmDecisionService::class.java)

    private val provenance = ModelProvenance(llm.name, llm.provider)

    private val retryTemplate = retry.retryTemplate(retryName)

    private val retryPrefix = retry.propertyPrefix

    override val name: String get() = llm.name

    override val provider: String get() = llm.provider

    /** Returns every question kind. */
    override fun capabilities(): DecisionCapabilities = CAPABILITIES

    override fun classify(request: ClassificationRequest): ClassificationResult =
        decide(CLASSIFY, { ClassificationResult.Failure(it) }) { attempts ->
            val answer = ask(CLASSIFY, PromptedClassification.messages(request), ClassificationAnswer::class.java, attempts)
            PromptedClassification.result(request, answer, provenance)
        }

    override fun assess(request: PropositionRequest): PropositionResult =
        decide(ASSESS, { PropositionResult.Failure(it) }) { attempts ->
            val answer = ask(ASSESS, PromptedProposition.messages(request), PropositionAnswer::class.java, attempts)
            PromptedProposition.result(answer, provenance)
        }

    /**
     * Answers every question of the request in one model call. A reply that cannot be matched to
     * the questions fails the whole request with [FailureReason.INVALID_RESPONSE]. An answer that
     * breaks its question's rules fails only that question.
     */
    override fun askNative(request: DecisionRequest): DecisionResponse =
        askQuestionSet(ASK, request)

    /** Answers one proposition question through the proposition prompt, with the question's instructions. */
    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult =
        assess(PropositionRequest(input, question.instructions))

    /** Rates the input against one rating question through the question set prompt, in its own model call. */
    override fun rate(input: String, question: RatingQuestionSpec): RatingResult =
        askQuestionSet(RATE, DecisionRequest.of(input, question)).answer(question)

    /**
     * Runs one question set through the model and turns the reply into a response for the request.
     *
     * @param operation the name used in retry and log lines
     * @param request the questions to ask, with the input text
     * @return the decision response for the request
     */
    private fun askQuestionSet(operation: String, request: DecisionRequest): DecisionResponse {
        val spec = request.spec
        return decide(operation, { DecisionResponse.failed(spec, it) }) { attempts ->
            val raw = ask(operation, PromptedQuestionSet.messages(request), String::class.java, attempts)
            if (DecisionContentCapture.isEnabled() && logger.isTraceEnabled) {
                logger.trace("Decision {} with model {} received model text: {}", operation, name, raw)
            }
            PromptedQuestionSet.response(spec, withoutCodeFence(raw), provenance, name)
        }
    }

    /**
     * Sends one prompt to the model and reads back the typed answer, retrying on failure.
     *
     * @param operation the name used in retry and log lines
     * @param messages the prompt to send
     * @param answerType the class the reply parses into
     * @param attempts counts the calls made, for the failure log line
     * @return the parsed answer
     */
    private fun <A : Any> ask(operation: String, messages: List<Message>, answerType: Class<A>, attempts: Attempts): A =
        retryTemplate.execute<A, Exception> {
            attempts.count++
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
     * which it throws as a [CancellationException].
     *
     * An interruption here is an [InterruptedException] in the cause chain. That covers an
     * interrupted model call, which [guarded] has already turned into one, and an interrupted wait
     * between retries. The interrupt flag alone never turns a failure into an interruption here, so
     * an answer that breaks the rules stays an invalid response when the caller's flag was set.
     *
     * The failure keeps only the reason. A failure is logged at WARN with the service, provider,
     * operation, reason, cause category, HTTP status class, attempt count, elapsed time and
     * the exception's class name. The exception's message is left out because provider errors can
     * echo the request. The shared model-call path and the retry listener log failed attempts on
     * their own terms.
     *
     * @param operation the name used in retry and log lines
     * @param failure builds the result for a mapped failure reason
     * @param work runs the decision, given the attempt counter to increment on each try
     * @return the result of [work], or the mapped failure
     */
    private fun <R : Any> decide(
        operation: String,
        failure: (FailureReason) -> R,
        work: (Attempts) -> R,
    ): R {
        val attempts = Attempts()
        val started = System.nanoTime()
        val result = try {
            work(attempts)
        } catch (e: DecisionInterrupted) {
            logger.debug("Decision {} with model {} was interrupted", operation, name)
            throw cancelled(e.interrupted)
        } catch (e: Exception) {
            // The retry template reports an interrupted backoff wait as its own exception, with the
            // InterruptedException as the cause.
            interruptionIn(e)?.let { interrupted ->
                logger.debug("Decision {} with model {} was interrupted", operation, name)
                throw cancelled(interrupted)
            }
            val reason = when (e) {
                is InvalidLlmReturnFormatException, is InvalidDecisionAnswerException -> FailureReason.INVALID_RESPONSE
                else -> FailureReason.UNAVAILABLE
            }
            val status = httpStatus(e)
            logger.warn(
                "Decision call failed: service={}, provider={}, operation={}, reason={}, cause={}, " +
                    "httpStatus={}, attempts={}, elapsedMs={}, exception={}. {}",
                name, provider, operation, reason, causeCategory(e, status),
                status?.let { "${it / 100}xx" } ?: "none", attempts.count, (System.nanoTime() - started) / 1_000_000,
                e.javaClass.simpleName, remedy(reason),
            )
            return failure(reason)
        }
        logger.debug("Decision {} with model {} returned {}", operation, name, result.javaClass.simpleName)
        return result
    }

    /**
     * Returns the log line's suggested fix for a failure reason.
     *
     * @param reason the failure reason
     * @return remedy text for the log line
     */
    private fun remedy(reason: FailureReason): String =
        if (reason == FailureReason.INVALID_RESPONSE) {
            "The model's reply did not follow the answer format. Check that the model can produce JSON output."
        } else {
            "Check the model's availability, credentials and retry settings under $retryPrefix."
        }

    /**
     * Names the kind of failure from its cause chain. The category never holds provider text.
     *
     * @param e the failure to categorize
     * @param status the HTTP status code found in its cause chain, if any
     * @return a short category name for the log line
     */
    private fun causeCategory(e: Exception, status: Int?): String {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            chain.any { it is InvalidLlmReturnFormatException || it is InvalidDecisionAnswerException } -> "invalid_response"
            status == TOO_MANY_REQUESTS || chain.any { LlmRetryDecision.isRateLimit(it) } -> "rate_limited"
            status != null && status in 400..499 -> "http_4xx"
            status != null && status in 500..599 -> "http_5xx"
            chain.any { it is HttpConnectTimeoutException } -> "connection"
            chain.any { it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException } -> "timeout"
            chain.any { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException } -> "connection"
            else -> "other"
        }
    }

    /**
     * Finds the HTTP status code carried by a REST client failure in the cause chain, if there is one.
     *
     * @param e the failure to search
     * @return the HTTP status code, or null when none is found
     */
    private fun httpStatus(e: Throwable): Int? =
        generateSequence(e) { it.cause }.filterIsInstance<RestClientResponseException>().firstOrNull()?.statusCode?.value()

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
     *
     * @param call the model call to run
     * @return what the call returns
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
     *
     * @param e the failure to search
     * @return the interruption, or null when the chain has none
     */
    private fun interruptionIn(e: Throwable): InterruptedException? =
        generateSequence(e) { it.cause }.filterIsInstance<InterruptedException>().firstOrNull()
            ?.also { Thread.currentThread().interrupt() }

    /**
     * The unchecked exception an interruption escapes as, so callers never face a checked one.
     *
     * @param interrupted the interruption, kept as the cause
     * @return the exception to throw
     */
    private fun cancelled(interrupted: InterruptedException): CancellationException =
        CancellationException("Decision interrupted").apply { initCause(interrupted) }

    /** Carries an interrupted call out of the retry template, which never retries it. */
    private class DecisionInterrupted(val interrupted: InterruptedException) :
        RuntimeException(interrupted), NonRetryable

    /** Counts the model calls made for one decision, including retries. */
    private class Attempts {
        var count = 0
    }

    private companion object {
        const val CLASSIFY = "classify"
        const val ASSESS = "assess"
        const val ASK = "ask"
        const val RATE = "rate"

        const val TOO_MANY_REQUESTS = 429

        val CAPABILITIES: DecisionCapabilities = DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))

        // One fenced block and nothing else around it: an opening fence with an optional json tag,
        // a line break, the content, and a closing fence. Content holding another fence is left
        // alone, so two fenced blocks stay an unreadable reply.
        val CODE_FENCE = Regex("""\A\s*```(?:json)?[ \t]*\r?\n(.*?)\r?\n?```\s*\z""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Returns the content of one Markdown code fence that wraps the whole reply, or the reply
         * unchanged. Models often fence JSON even when told not to.
         */
        fun withoutCodeFence(raw: String): String {
            val content = CODE_FENCE.matchEntire(raw)?.groupValues?.get(1) ?: return raw
            return if (content.contains("```")) raw else content
        }
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
