/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.eval.support

import com.embabel.agent.eval.assert.Assertion
import com.embabel.agent.eval.client.GenerationEvent
import com.embabel.agent.eval.client.MessageRole
import com.embabel.agent.eval.client.OpenAiCompatibleMessage
import com.embabel.agent.eval.client.SessionCreationRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min

data class Evaluator(
    val name: String = "Socrates",
    val voice: String = "You speak like a friendly, relaxed human",
    val prompt: String = "eval/socrates",
    val temperature: Double = 0.5,
    val signoff: String = "So Long, and Thanks for All the Fish",
)

data class Scorer(
    val prompt: String = "eval/score",
    val temperature: Double = 0.0,
)

enum class TaskType {
    question, task
}

data class Task(
    val type: TaskType = TaskType.task,
    val task: String,
    val acceptanceCriteria: String,
)

/**
 * Configuration for evaluating a conversation
 * @param evaluator the evaluator
 * @param tasks the tasks to perform
 * @param facts the facts to provide in response to questions
 * @param maxTurns the maximum number of turns in the conversation. Safeguard
 * @param assertions assertions to make after the conversation is done
 */
data class EvaluationJob(
    val evaluator: Evaluator,
    val aspirationalAverage: Long,
    val target: SessionCreationRequest,
    val scorer: Scorer = Scorer(),
    val maxTurns: Int = 20,
    val greetings: List<String> = listOf("Hello", "Hi", "Greetings", "Yo", "Hey"),
    val setups: List<Setup> = emptyList(),
    val tasks: List<Task>,
    val facts: List<String>,
    val assertions: List<Assertion> = emptyList(),
) {

    companion object {

        private val yom = ObjectMapper(YAMLFactory()).registerKotlinModule()

        fun fromYml(location: String, resourceLoader: ResourceLoader = DefaultResourceLoader()): EvaluationJob {
            return yom.readValue<EvaluationJob>(
                resourceLoader.getResource(location).inputStream, EvaluationJob::class.java
            )
        }
    }
}

/**
 * A score for a question or fact from 0-1
 */
data class Score(
    val scored: String,
    val score: Double,
)

/**
 * @param events events that occurred during generation. Only relevant for
 * assistant messages.
 */
data class TimedOpenAiCompatibleMessage(
    override val content: String,
    override val role: MessageRole,
    val timeTakenMillis: Long,
    val events: List<GenerationEvent>,
) : OpenAiCompatibleMessage

interface EvaluationRun {

    val job: EvaluationJob

    /**
     * @return transcript of messages with evaluator as user and Agent as assistant
     */
    val transcript: List<TimedOpenAiCompatibleMessage>
}

/**
 * LLM-generated scores for tasks
 */
data class SubjectiveScores(
    val tone: Double,
    val tasks: List<Score>,
) {

    fun averageTaskScore() = tasks.map { it.score }.average()
}

data class ResponseTimes(
    val responses: Int,
    val average: Int,
    val max: Int,
)

data class FullScores(
    val failureCount: Int,
    val subjectiveScores: SubjectiveScores,
    val assertionScores: List<Score>,
    val responseTimes: ResponseTimes,
    /**
     * Most meaningful score
     */
    val totalScore: Double,
)

interface EvaluationResultScorer {

    fun score(evaluationResult: EvaluationResult): FullScores
}

data class EvaluationResults(
    val results: List<EvaluationResult>,
) {

    fun report(): String {
        return results.joinToString("\n\n") { it.report() }
    }
}

data class EvaluationResult(
    override val job: EvaluationJob,
    val aborted: Boolean = false,
    val failureCount: Int,
    val subjectiveScores: SubjectiveScores,
    val assertionScores: List<Score>,
    override val transcript: List<TimedOpenAiCompatibleMessage>,
    val factsSupplied: Boolean,
) : EvaluationRun {

    fun assistantMessages() = transcript.filter { it.role == MessageRole.assistant }

    fun responseTimes(): ResponseTimes {
        val times = assistantMessages().map { it.timeTakenMillis }
        val average = if (times.isNotEmpty()) times.sum() / times.size else 0
        val max = times.maxOrNull() ?: 0
        return ResponseTimes(responses = assistantMessages().size, average = average.toInt(), max = max.toInt())
    }

    fun report(): String {
        return """|
            |Evaluation by ${job.evaluator.name}
            |${job.tasks.size} tasks, ${job.facts.size} facts, factsSupplied=$factsSupplied
            |${transcript.size} messages
            |$subjectiveScores
            |${assertionScores.joinToString("\n")}
            |Response times: ${responseTimes()}
        """.trimMargin()
    }

}

data class EvaluationOptions(
    val verbose: Boolean,
    val model: String,
)

interface EvaluationRunner {

    fun evaluateConversation(
        evaluationJob: EvaluationJob,
        options: EvaluationOptions,
    ): EvaluationResults
}

class DefaultEvaluationResultScorer : EvaluationResultScorer {

    private val logger = LoggerFactory.getLogger(DefaultEvaluationResultScorer::class.java)

    override fun score(evaluationResult: EvaluationResult): FullScores {
        val timingScore = min(
            1.0,
            evaluationResult.job.aspirationalAverage.toDouble() / evaluationResult.responseTimes().average.toDouble(),
        )
        val taskScore = evaluationResult.subjectiveScores.averageTaskScore()
        val assertionTotal = evaluationResult.job.assertions.sumOf { it.weight }
        val assertionScore =
            evaluationResult.assertionScores.sumOf {
                it.score * evaluationResult.job.assertions.single { a -> a.name == it.scored }.weight
            } / assertionTotal
        val failurePenalty = min(evaluationResult.failureCount * .3, 0.5)
        val overallScore = max(0.0, (timingScore + taskScore * 2.0 + assertionScore * 4.0) / 7.0 - failurePenalty)
        logger.info("Timing score: $timingScore, task score: $taskScore, assertion score: $assertionScore, failure penalty: $failurePenalty, overall score: $overallScore")
        return FullScores(
            subjectiveScores = evaluationResult.subjectiveScores,
            assertionScores = evaluationResult.assertionScores,
            responseTimes = evaluationResult.responseTimes(),
            totalScore = BigDecimal(overallScore).setScale(2, RoundingMode.HALF_EVEN).toDouble(),
            failureCount = evaluationResult.failureCount,
        )
    }
}
