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
package com.embabel.examples.simple.horoscope.kotlin

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.createObject
import com.embabel.agent.api.common.createObjectIfPossible
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.domain.library.Person
import com.embabel.agent.domain.library.RelevantNewsStories
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.Auto
import com.embabel.examples.simple.horoscope.HoroscopeService
import com.embabel.ux.form.Text
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.springframework.beans.factory.annotation.Value

/**
 * Data class representing astrological details for a person.
 * Used to capture star sign information through a form interface.
 */
@JsonClassDescription("Astrological details for a person")
data class Starry(
    @Text(label = "Star sign")
    val sign: String,
)

/**
 * Data class representing a person with their astrological details.
 * Implements the Person interface to maintain compatibility with the agent framework's
 * person-related operations.
 */
@JsonClassDescription("Person with astrology details")
@JsonDeserialize(`as` = StarPerson::class)
data class StarPerson(
    override val name: String,
    @get:JsonPropertyDescription("Star sign")
    val sign: String,
) : Person

/**
 * Data class containing a person's horoscope summary.
 * Acts as a container for the horoscope text retrieved from the HoroscopeService.
 */
data class Horoscope(
    val summary: String,
)

/**
 * Data class representing the final output of the agent's workflow.
 * Implements HasContent interface to provide standardized access to the text content.
 */
data class Writeup(
    override val content: String,
) : HasContent

/**
 * An agent that finds personalized news stories based on a person's star sign.
 *
 * This agent demonstrates the workflow of:
 * 1. Extracting person information from user input
 * 2. Obtaining astrological details
 * 3. Retrieving a horoscope
 * 4. Finding relevant news stories based on the horoscope
 * 5. Creating a personalized writeup combining the horoscope and news
 *
 * The agent leverages Spring dependency injection for services and uses
 * the annotation-driven programming model with @Agent and @Action annotations
 * to define its capabilities and workflow.
 */
@Agent(
    description = "Find news based on a person's star sign",
    scan = true,
    beanName = "KotlinStarNewsFinder",
)
class StarNewsFinder(
    private val horoscopeService: HoroscopeService,
    @Value("\${star-news-finder.model:gpt-4.1-mini}")
//    @Value("\${star-news-finder.model:ai/llama3.2}")

    private val model: String = OpenAiModels.GPT_41_NANO,
    @Value("\${star-news-finder.story.count:5}")
    private val storyCount: Int,
    @Value("\${star-news-finder.word.count:100}")
    private val wordCount: Int,
) {

    /**
     * Extracts a person entity from user input by parsing the text for a name.
     *
     * This method uses a lightweight LLM model (GPT-41-NANO) to efficiently extract
     * just the person's name from the user's input text. It's an entry point for
     * the agent workflow when only basic person information is available.
     *
     * @param userInput The user's text input
     * @return A Person object if extraction is successful, null otherwise
     */
    @Action
    fun extractPerson(userInput: UserInput): Person? =
        // All prompts are typesafe
        usingDefaultLlm.createObjectIfPossible(
            """
            Create a person from this user input, extracting their name:
            ${userInput.content}
            """.trimIndent()
        )

    /**
     * Collects astrological details for a person through a form interface.
     *
     * This method is marked with a high cost (100.0) to indicate that it should
     * only be used when no other path is available in the agent's planning process.
     * The high cost discourages the agent from asking for user input unless necessary.
     *
     * @param person The person for whom to collect star sign information
     * @return A Starry object containing the person's star sign
     */
    @Action(cost = 100.0) // Make it costly so it won't be used in a plan unless there's no other path
    internal fun makeStarry(
        person: Person,
    ): Starry =
        fromForm("Let's get some astrological details for ${person.name}")

    /**
     * Combines a person and their astrological details into a StarPerson object.
     *
     * This method serves as a data transformation step in the agent workflow,
     * creating a specialized person object that includes star sign information.
     *
     * @param person The basic person information
     * @param starry The astrological details
     * @return A StarPerson object combining both sets of information
     */
    @Action
    fun assembleStarPerson(
        person: Person,
        starry: Starry,
    ): StarPerson {
        return StarPerson(
            name = person.name,
            sign = starry.sign,
        )
    }

    /**
     * Extracts both person information and star sign directly from user input.
     *
     * This method provides an alternative entry point to the agent workflow,
     * allowing the extraction of both name and star sign in a single step when
     * that information is present in the user's input.
     *
     * @param userInput The user's text input
     * @return A StarPerson object if extraction is successful, null otherwise
     */
    @Action
    fun extractStarPerson(userInput: UserInput): StarPerson? =
        using(LlmOptions(Auto)).createObjectIfPossible(
            """
            Create a person from this user input, extracting their name and star sign:
            ${userInput.content}
            """.trimIndent()
        )

    /**
     * Retrieves a daily horoscope for a person based on their star sign.
     *
     * This method calls the injected HoroscopeService to get the actual horoscope text,
     * wrapping it in a Horoscope data class for use in subsequent steps.
     *
     * @param starPerson The person with their star sign information
     * @return A Horoscope object containing the daily horoscope text
     */
    @Action
    fun retrieveHoroscope(starPerson: StarPerson) =
        Horoscope(horoscopeService.dailyHoroscope(starPerson.sign))

    /**
     * Finds news stories relevant to a person's horoscope using web search tools.
     *
     * This method requires web tools (specified by toolGroups) to search for and
     * summarize news stories that relate to themes in the person's horoscope.
     * It uses the LLM to interpret the horoscope, generate appropriate search
     * queries, and summarize the results.
     *
     * @param person The person with their star sign
     * @param horoscope The person's daily horoscope
     * @return A collection of relevant news stories with summaries and URLs
     */
    // toolGroups specifies tools that are required for this action to run
    @Action(toolGroups = [CoreToolGroups.WEB, CoreToolGroups.BROWSER_AUTOMATION, "rag"])
    internal fun findNewsStories(person: StarPerson, horoscope: Horoscope): RelevantNewsStories =
        usingModel(model).createObject(
            """
            ${person.name} is an astrology believer with the sign ${person.sign}.
            Their horoscope for today is:
                <horoscope>${horoscope.summary}</horoscope>
            Given this, use web tools and generate search queries
            to find $storyCount relevant news stories summarize them in a few sentences.
            Include the URL for each story.
            Do not look for another horoscope reading or return results directly about astrology;
            find stories relevant to the reading above.

            For example:
            - If the horoscope says that they may
            want to work on relationships, you could find news stories about
            novel gifts
            - If the horoscope says that they may want to work on their career,
            find news stories about training courses.
            """.trimIndent()
        )

    /**
     * Creates a personalized writeup combining the horoscope and relevant news stories.
     *
     * This method is the final step in the agent's workflow, marked with @AchievesGoal
     * to indicate that it fulfills the agent's primary purpose. It uses the LLM with
     * increased temperature (0.9) to generate creative content that combines the
     * horoscope interpretation with the found news stories in an amusing way.
     *
     * @param person The person with their star sign
     * @param relevantNewsStories The collection of news stories found
     * @param horoscope The person's daily horoscope
     * @return A Writeup containing the formatted text combining horoscope and news
     */
    // The @AchievesGoal annotation indicates that completing this action
    // achieves the given goal, so the agent flow can be complete
    @AchievesGoal(
        description = "Create an amusing writeup for the target person based on their horoscope and current news stories",
    )
    @Action
    fun starNewsWriteup(
        person: StarPerson,
        relevantNewsStories: RelevantNewsStories,
        horoscope: Horoscope,
    ): Writeup = using(
        LlmOptions(model).withTemperature(.9)
    ).createObject<Writeup>(
        """
        Take the following news stories and write up something
        amusing for the target person in $wordCount words.

        Begin by summarizing their horoscope in a concise, amusing way, then
        talk about the news. End with a surprising signoff.

        ${person.name} is an astrology believer with the sign ${person.sign}.
        Their horoscope for today is:
            <horoscope>${horoscope.summary}</horoscope>
        Relevant news stories are:
        ${relevantNewsStories.items.joinToString("\n") { "- ${it.url}: ${it.summary}" }}

        Format it as Markdown with links.
        """.trimIndent()
    )

}
