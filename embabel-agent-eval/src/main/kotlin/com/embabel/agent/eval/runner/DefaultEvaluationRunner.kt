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
package com.embabel.agent.eval.runner

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.eval.assert.AssertionEvaluator
import com.embabel.agent.eval.client.*
import com.embabel.agent.eval.support.*
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.entity
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplateBuilder


private val SCORES_EXAMPLE = SubjectiveScores(
    tone = 0.5,
    tasks = listOf(
        Score("What is the capital of France?", 0.9),
        Score("Who was the first President of France", 0.4),
        Score("Tell me a joke", 0.7),
        Score("Tell me a story in 50 words", 0.6),
    ),
)

class DefaultEvaluationRunner(
    private val evaluatorChatModel: ChatModel,
    private val scoringChatModel: ChatModel = evaluatorChatModel,
    private val templateRenderer: TemplateRenderer,
    private val setupRunner: SetupRunner,
    private val assertionEvaluator: AssertionEvaluator,
    private val agentChatClient: AgentChatClient,
) : EvaluationRunner {

    private val logger = LoggerFactory.getLogger(DefaultEvaluationRunner::class.java)

    override fun evaluateConversation(
        evaluationJob: EvaluationJob,
        options: EvaluationOptions,
    ): EvaluationResults {
        evaluationJob.setups.forEach { setupRunner.execute(it) }
        val one = evaluateConversation(evaluationJob, options, supplyFacts = false)
        val two = evaluateConversation(evaluationJob, options, supplyFacts = true)
        return EvaluationResults(listOf(one, two))
    }

    private fun evaluateConversation(
        evaluationJob: EvaluationJob,
        options: EvaluationOptions,
        supplyFacts: Boolean,
    ): EvaluationResult {

        val session = agentChatClient.createSession(evaluationJob.target)
        val evaluationInProgress =
            EvaluationInProgress(
                sessionId = session.sessionId,
                job = evaluationJob,
                model = options.model,
            )
        val retryTemplate = RetryTemplateBuilder().maxAttempts(4).fixedBackoff(100)
            .withListener(object : RetryListener {
                override fun <T : Any?, E : Throwable?> onError(
                    context: RetryContext?,
                    callback: RetryCallback<T?, E?>?,
                    throwable: Throwable?,
                ) {
                    evaluationInProgress.recordFailure()
                }
            })
            .build()

        println("=".repeat(130))
        println("Beginning conversation evaluation: supplyFacts=$supplyFacts, sessionId=${session.sessionId}, model=${options.model}")
        println("=".repeat(130))

        val helloRequest = ChatRequest(
            sessionId = session.sessionId,
            message = evaluationJob.greetings.random(),
            model = options.model,
        )
        evaluationInProgress.addEvaluatorUserMessage(helloRequest.message.content, 0)
        println("${evaluationJob.evaluator.name}: ${helloRequest.message.content}")
        var (agentResponse, timeTakenMillis) = time {
            agentChatClient.respond(helloRequest)
        }
        evaluationInProgress.addAssistantMessage(
            content = agentResponse.message.content,
            timeTakenMillis = timeTakenMillis,
            events = agentResponse.events,
        )

        while (evaluationInProgress.transcript.size < evaluationJob.maxTurns) {
            if (options.verbose) {
                logEvents(agentResponse.events)
            }
            println("${agentResponse.chatbot}: ${agentResponse.message.content}")
            val (evaluatorReply, _) = time {
                generateEvaluatorReply(
                    evaluationInProgress = evaluationInProgress,
                    sessionId = session.sessionId,
                )
            }
            println("${evaluationJob.evaluator.name}: ${evaluatorReply.message.content}")
            if (evaluatorReply.message.content.contains(evaluationJob.evaluator.signoff, ignoreCase = true)) {
                println("=".repeat(130))
                println("Goals achieved: Scoring...")
                evaluationInProgress.done = true
                val assertionScores = evaluationJob.assertions.map {
                    assertionEvaluator.evaluate(evaluationInProgress = evaluationInProgress, assertion = it)
                }
                val subjectiveScores = scoreTranscript(evaluationInProgress)
                val evaluationResult = EvaluationResult(
                    job = if (supplyFacts) evaluationJob else evaluationJob.copy(facts = emptyList()),
                    subjectiveScores = subjectiveScores,
                    assertionScores = assertionScores,
                    transcript = evaluationInProgress.transcript,
                    factsSupplied = supplyFacts,
                    failureCount = evaluationInProgress.failureCount,
                )
                subjectiveScores.tasks.forEach {
                    val mark = if (it.score > .6) "✅" else "❌"
                    logger.info("{} Subjective assessment={}: {}", mark, it.score, it.scored)
                }
//                println(evaluationResult)
//                println(evaluationResult.responseTimes())
                return evaluationResult
            }
            val newTimingResult = time {
                retryTemplate.execute<MessageResponse, Throwable> {
                    agentChatClient.respond(evaluatorReply)
                }
            }
            agentResponse = newTimingResult.first
            timeTakenMillis = newTimingResult.second
            evaluationInProgress.addAssistantMessage(
                content = agentResponse.message.content,
                timeTakenMillis = timeTakenMillis,
                events = agentResponse.events,
            )
        }

        // Failure if we got here with timeout
        val assertionScores = evaluationJob.assertions.map {
            assertionEvaluator.evaluate(evaluationInProgress = evaluationInProgress, assertion = it)
        }
        return EvaluationResult(
            job = evaluationJob,
            aborted = true,
            subjectiveScores = scoreTranscript(evaluationInProgress),
            assertionScores = assertionScores,
            transcript = evaluationInProgress.transcript,
            failureCount = evaluationInProgress.failureCount,
            factsSupplied = supplyFacts,
        )
    }

    private fun logEvents(events: List<GenerationEvent>) {
        fun colorFor(event: GenerationEvent) = when (event) {
            is FunctionResponseEvent -> "\u001B[31m"  // Red
            is FunctionCallEvent -> "\u001B[33m"   // Yellow
            is SystemPromptEvent -> "\u001B[90m"  // Gray
            is BoogieRequestEvent -> "\u001B[32m"  // Green
        }
        events.forEach { event ->
            val color = colorFor(event)
            println("$color$event\u001B[0m")
        }
    }

    private fun generateEvaluatorReply(
        evaluationInProgress: EvaluationInProgress,
        sessionId: String,
    ): ChatRequest {
        val chatOptions = ChatOptions.builder()
            .model(OpenAiModels.GPT_41_MINI)
            .temperature(evaluationInProgress.job.evaluator.temperature)
            .build()
        val systemPrompt = templateRenderer.renderLoadedTemplate(
            evaluationInProgress.job.evaluator.prompt,
            mapOf(
                "config" to evaluationInProgress.job,
                "transcript" to evaluationInProgress.transcript,
            )
        )
//        println(systemPrompt)
        val timedChatResponse = time {
            evaluatorChatModel
                .call(Prompt(systemPrompt, chatOptions))
        }
        val reply = ChatRequest(
            sessionId = sessionId,
            message = timedChatResponse.first.result.output.text!!,
            model = evaluationInProgress.model,
        )
        evaluationInProgress.addEvaluatorUserMessage(
            content = reply.message.content,
            timeTakenMillis = timedChatResponse.second
        )
        return reply
    }

    private fun scoreTranscript(
        evaluationRun: EvaluationRun,
    ): SubjectiveScores {
        val scoringChatOptions = ChatOptions.builder()
            .temperature(evaluationRun.job.scorer.temperature)
            .build()
        val prompt = templateRenderer.renderLoadedTemplate(
            evaluationRun.job.scorer.prompt,
            mapOf(
                "config" to evaluationRun.job,
                "transcript" to evaluationRun.transcript,
                "example" to jacksonObjectMapper().registerModule(JavaTimeModule()).writerWithDefaultPrettyPrinter()
                    .writeValueAsString(SCORES_EXAMPLE),
            )
        )
        val chatClient = ChatClient
            .builder(scoringChatModel)
            .defaultOptions(scoringChatOptions)
            .build()
        return chatClient.prompt(prompt).call()
            .entity<SubjectiveScores>()
    }
}

fun <T> time(block: () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = block()
    return Pair(result, System.currentTimeMillis() - start)
}
