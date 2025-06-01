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
package com.embabel.examples.dogfood.research

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.config.models.AnthropicModels
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.ResearchReport
import com.embabel.agent.experimental.prompt.Persona
import com.embabel.agent.experimental.prompt.PromptUtils
import com.embabel.agent.experimental.prompt.ResponseFormat
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byRole
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import com.embabel.common.core.types.Timestamped
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant

data class SingleLlmReport(
    val report: ResearchReport,
    val model: String,
) : Timestamped {
    override val timestamp: Instant = Instant.now()
}

data class Critique(
    val accepted: Boolean,
    val reasoning: String,
)


@ConfigurationProperties(prefix = "embabel.examples.researcher")
data class ResearcherProperties(
    val responseFormat: ResponseFormat = ResponseFormat.MARKDOWN,
    val maxWordCount: Int = 300,
    val claudeModelName: String = AnthropicModels.CLAUDE_35_HAIKU,
    val openAiModelName: String = OpenAiModels.GPT_41_MINI,
    val criticModeName: String = OpenAiModels.GPT_41,
    val mergeModelName: String = OpenAiModels.GPT_41_MINI,
    override val name: String = "Sherlock",
    override val persona: String = "A resourceful researcher agent that can perform deep web research on a topic. Nothing escapes Sherlock",
    override val voice: String = "Your voice is dry and in the style of Sherlock Holmes. Occasionally you address the user as Watson",
    override val objective: String = "To clarify all points the user has brought up",
) : Persona, PromptContributorConsumer {
    override val promptContributors: List<PromptContributor>
        get() = listOf(
            responseFormat,
            this,
        )

}

enum class Category {
    QUESTION,
    DISCUSSION,
}

data class Categorization(
    val category: Category,
)

/**
 * Researcher agent that implements the Embabel model for autonomous research.
 *
 * This agent demonstrates several key aspects of the Embabel framework:
 * 1. Multi-model approach - using both GPT-4 and Claude models for research
 * 2. Self-critique and improvement - evaluating reports and redoing research if needed
 * 3. Parallel execution - running multiple research actions concurrently
 * 4. Workflow control with conditions - using satisfactory/unsatisfactory conditions
 * 5. Model merging - combining results from different LLMs for better output
 *
 * The agent follows a structured workflow:
 * - First categorizes user input as a question or discussion topic
 * - Performs research using multiple LLM models in parallel
 * - Merges the research reports from different models
 * - Self-critiques the merged report
 * - If unsatisfactory, reruns research with specific models
 * - Delivers the final research report when satisfactory
 */
@Agent(
    description = "Perform deep web research on a topic",
)
class Researcher(
    val properties: ResearcherProperties,
) {

    private val logger = LoggerFactory.getLogger(Researcher::class.java)

    init {
        logger.info("Researcher agent initialized: $properties")
    }

    /**
     * Categorizes the user input to determine the appropriate research approach.
     * Uses the cheapest LLM model to efficiently classify the input.
     *
     * @param userInput The user's query or topic for research
     * @return Categorization of the input as either a QUESTION or DISCUSSION
     */
    @Action
    fun categorize(
        userInput: UserInput,
    ): Categorization = using(
        llm = LlmOptions(byRole(CHEAPEST_ROLE)),
    ).create(
        """
        Categorize the following user input:

        Topic:
        <${userInput.content}>
    """.trimIndent()
    )

    /**
     * Performs research using the GPT-4 model.
     * This is one of two parallel research paths (along with Claude).
     *
     * @param userInput The user's query or topic
     * @param categorization The categorization of the input
     * @param context The operation context for accessing tools and services
     * @return A research report with the GPT-4 model's findings
     */
    // These need a different output binding or only one will run
    @Action(
        post = [REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
        toolGroups = [CoreToolGroups.WEB, CoreToolGroups.BROWSER_AUTOMATION]
    )
    fun researchWithGpt4(
        userInput: UserInput,
        categorization: Categorization,
        context: OperationContext,
    ): SingleLlmReport = researchWith(
        userInput = userInput,
        categorization = categorization,
        critique = null,
        llm = LlmOptions(properties.openAiModelName),
        context = context,
    )

    /**
     * Redoes research with GPT-4 after receiving an unsatisfactory critique.
     * This demonstrates the agent's ability to improve based on feedback.
     *
     * @param userInput The user's query or topic
     * @param categorization The categorization of the input
     * @param critique The critique of the previous report explaining why it was unsatisfactory
     * @param context The operation context for accessing tools and services
     * @return An improved research report with the GPT-4 model's findings
     */
    @Action(
        pre = [REPORT_UNSATISFACTORY],
        post = [REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
        toolGroups = [CoreToolGroups.WEB, CoreToolGroups.BROWSER_AUTOMATION]
    )
    fun redoResearchWithGpt4(
        userInput: UserInput,
        categorization: Categorization,
        critique: Critique,
        context: OperationContext,
    ): SingleLlmReport = researchWith(
        userInput = userInput,
        categorization = categorization,
        critique = critique,
        llm = LlmOptions(properties.openAiModelName),
        context = context,
    )

    /**
     * Performs research using the Claude model.
     * This is one of two parallel research paths (along with GPT-4).
     *
     * @param userInput The user's query or topic
     * @param categorization The categorization of the input
     * @param context The operation context for accessing tools and services
     * @return A research report with the Claude model's findings
     */
    @Action(
        post = [REPORT_SATISFACTORY],
        outputBinding = "claudeReport",
        canRerun = true,
        toolGroups = [CoreToolGroups.WEB, CoreToolGroups.BROWSER_AUTOMATION]
    )
    fun researchWithClaude(
        userInput: UserInput,
        categorization: Categorization,
        context: OperationContext,
    ): SingleLlmReport = researchWith(
        userInput = userInput,
        categorization = categorization,
        critique = null,
        llm = LlmOptions(properties.claudeModelName),
        context = context,
    )

    /**
     * Redoes research with Claude after receiving an unsatisfactory critique.
     * This demonstrates the agent's ability to improve based on feedback.
     *
     * @param userInput The user's query or topic
     * @param categorization The categorization of the input
     * @param critique The critique of the previous report explaining why it was unsatisfactory
     * @param context The operation context for accessing tools and services
     * @return An improved research report with the Claude model's findings
     */
    @Action(
        pre = [REPORT_UNSATISFACTORY],
        post = [REPORT_SATISFACTORY],
        outputBinding = "claudeReport",
        canRerun = true,
        toolGroups = [CoreToolGroups.WEB, CoreToolGroups.BROWSER_AUTOMATION]
    )
    fun redoResearchWithClaude(
        userInput: UserInput,
        categorization: Categorization,
        critique: Critique,
        context: OperationContext,
    ): SingleLlmReport = researchWith(
        userInput = userInput,
        categorization = categorization,
        critique = critique,
        llm = LlmOptions(properties.claudeModelName),
        context = context,
    )

    /**
     * Common implementation for research with different models.
     * Routes to the appropriate research method based on categorization.
     *
     * @param userInput The user's query or topic
     * @param categorization The categorization of the input
     * @param critique Optional critique from a previous attempt
     * @param llm The LLM options including model selection
     * @param context The operation context for accessing tools and services
     * @return A research report with the specified model's findings
     */
    private fun researchWith(
        userInput: UserInput,
        categorization: Categorization,
        critique: Critique?,
        llm: LlmOptions,
        context: OperationContext,
    ): SingleLlmReport {
        val researchReport = when (
            categorization.category
        ) {
            Category.QUESTION -> answerQuestion(userInput, llm, critique, context)
            Category.DISCUSSION -> research(userInput, llm, critique, context)
        }
        return SingleLlmReport(
            report = researchReport,
            model = llm.criteria.toString(),
        )
    }

    /**
     * Generates a research report that answers a specific question.
     * Uses web tools to find precise answers with citations.
     *
     * @param userInput The user's question
     * @param llm The LLM options including model selection
     * @param critique Optional critique from a previous attempt
     * @param context The operation context for accessing tools and services
     * @return A research report answering the question
     */
    private fun answerQuestion(
        userInput: UserInput,
        llm: LlmOptions,
        critique: Critique?,
        context: OperationContext,
    ): ResearchReport = context.promptRunner(
        llm = llm,
        promptContributors = properties.promptContributors,
    ).create(
        """
        Use the web and browser tools to answer the given question.

        You must try to find the answer on the web, and be definite, not vague.

        Write a detailed report in at most ${properties.maxWordCount} words.
        If you can answer the question more briefly, do so.
        Including a number of links that are relevant to the topic.

        Example:
        ${PromptUtils.jsonExampleOf<ResearchReport>()}

        Question:
        <${userInput.content}>

        ${
            critique?.reasoning?.let {
                "Critique of previous answer:\n<$it>"
            }
        }
    """.trimIndent()
    )

    /**
     * Generates a research report on a discussion topic.
     * Uses web tools to gather information and provide a comprehensive overview.
     *
     * @param userInput The user's topic for research
     * @param llm The LLM options including model selection
     * @param critique Optional critique from a previous attempt
     * @param context The operation context for accessing tools and services
     * @return A research report on the topic
     */
    private fun research(
        userInput: UserInput,
        llm: LlmOptions,
        critique: Critique?,
        context: OperationContext,
    ): ResearchReport = context.promptRunner(
        llm = llm,
        promptContributors = properties.promptContributors,
    ).create(
        """
        Use the web and browser tools to perform deep research on the given topic.

        Write a detailed report in ${properties.maxWordCount} words,
        including a number of links that are relevant to the topic.

        Topic:
        <${userInput.content}>

         ${
            critique?.reasoning?.let {
                "Critique of previous answer:\n<$it>"
            }
        }
    """.trimIndent()
    )

    /**
     * Evaluates the quality of the merged research report.
     * This implements the self-critique capability of the Embabel model.
     *
     * @param userInput The user's original query or topic
     * @param mergedReport The combined report to evaluate
     * @return A critique with acceptance status and reasoning
     */
    @Action(post = [REPORT_SATISFACTORY], canRerun = true)
    fun critiqueMergedReport(
        userInput: UserInput,
        @RequireNameMatch mergedReport: ResearchReport,
    ): Critique = using(LlmOptions(properties.criticModeName)).create(
        """
            Is this research report satisfactory? Consider the following question:
            <${userInput.content}>
            The report is satisfactory if it answers the question with adequate references.
            It is possible that the question does not have a clear answer, in which
            case the report is satisfactory if it provides a reasonable discussion of the topic.

            ${mergedReport.infoString(verbose = true)}
        """.trimIndent(),
    )

    /**
     * Combines the research reports from different models into a single, improved report.
     * This demonstrates the multi-model approach of the Embabel framework.
     *
     * @param userInput The user's original query or topic
     * @param gpt4Report The research report from the GPT-4 model
     * @param claudeReport The research report from the Claude model
     * @return A merged research report combining the best insights from both models
     */
    @Action(
        post = [REPORT_SATISFACTORY],
        outputBinding = "mergedReport",
        canRerun = true,
    )
    fun mergeReports(
        userInput: UserInput,
        @RequireNameMatch gpt4Report: SingleLlmReport,
        @RequireNameMatch claudeReport: SingleLlmReport,
    ): ResearchReport {
        val reports = listOf(
            gpt4Report,
            claudeReport,
        )
        return using(
            llm = LlmOptions(properties.criticModeName),
            promptContributors = properties.promptContributors,
        ).create(
            """
        Merge the following research reports into a single report taking the best of each.
        Consider the user direction: <${userInput.content}>

        ${reports.joinToString("\n\n") { "Report from ${it.model}\n${it.report.infoString(verbose = true)}" }}
    """.trimIndent()
        )
    }

    /**
     * Condition that determines if a report is satisfactory.
     * Used to control workflow progression.
     *
     * @param critique The critique of the report
     * @return True if the report is accepted as satisfactory
     */
    @Condition(name = REPORT_SATISFACTORY)
    fun makesTheGrade(
        critique: Critique,
    ): Boolean = critique.accepted

    /**
     * Condition that determines if a report is unsatisfactory.
     * Used to trigger rework of research.
     *
     * @param critique The critique of the report
     * @return True if the report is rejected as unsatisfactory
     */
    // TODO should be able to use !
    @Condition(name = REPORT_UNSATISFACTORY)
    fun rejected(
        critique: Critique,
    ): Boolean = !critique.accepted

    /**
     * Final action that accepts the research report as the agent's output.
     * This marks the successful completion of the research task.
     *
     * @param mergedReport The final merged research report
     * @param critique The positive critique confirming the report is satisfactory
     * @return The final research report
     */
    @AchievesGoal(
        description = "Completes a research or question answering task, producing a research report",
    )
    // TODO this won't complete without the output binding to a new thing.
    // This makes some sense but seems a bit surprising
    @Action(pre = [REPORT_SATISFACTORY], outputBinding = "finalResearchReport")
    fun acceptReport(
        @RequireNameMatch mergedReport: ResearchReport,
        critique: Critique,
    ) = mergedReport

    companion object {
        /** Condition name for when a report is satisfactory */
        const val REPORT_SATISFACTORY = "reportSatisfactory"

        /** Condition name for when a report is unsatisfactory */
        const val REPORT_UNSATISFACTORY = "reportUnsatisfactory"
    }
}
