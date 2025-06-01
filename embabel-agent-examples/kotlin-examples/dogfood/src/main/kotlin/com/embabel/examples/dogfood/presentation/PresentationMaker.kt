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
package com.embabel.examples.dogfood.presentation

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.DynamicExecutionResult
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.api.dsl.parallelMap
import com.embabel.agent.config.models.AnthropicModels
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.domain.io.FileArtifact
import com.embabel.agent.domain.library.CompletedResearch
import com.embabel.agent.domain.library.ResearchReport
import com.embabel.agent.domain.library.ResearchResult
import com.embabel.agent.domain.library.ResearchTopics
import com.embabel.agent.event.logging.personality.severance.LumonColorPalette
import com.embabel.agent.experimental.prompt.CoStar
import com.embabel.agent.shell.formatProcessOutput
import com.embabel.agent.tools.file.FileContentTransformer
import com.embabel.agent.tools.file.FileReadTools
import com.embabel.agent.tools.file.WellKnownFileContentTransformers.removeApacheLicenseHeader
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byName
import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.ResourceLoader
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption
import java.nio.charset.Charset

data class ImageInfo(val url: String, val useWhen: String)

/**
 * @param brief the content of the presentation. Can be short
 * or detailed
 * @param autoIllustrate ask the LLM to provide illustrations. Not yet dependable
 */
data class PresentationRequest(
    val slideCount: Int,
    val presenterBio: String,
    val brief: String,
    val softwareProject: String?,
    val outputDirectory: String = "/Users/rjohnson/Documents",
    val outputFile: String = "presentation.md",
    val header: String,
    val images: Map<String, ImageInfo> = emptyMap(),
    val autoIllustrate: Boolean = false,
    //val slidesToInclude: String,
    val coStar: CoStar,
) : PromptContributor by coStar {

    @JsonIgnore
    val project: Project? =
        softwareProject?.let {
            Project(it)
        }

    /**
     * File name for interim artifact with raw deck
     */
    fun rawOutputFile(): String {
        return outputFile.replace(".md", ".raw.md")
    }

    fun withDiagramsOutputFile(): String {
        return outputFile.replace(".md", ".withDiagrams.md")
    }
}

@ConfigurationProperties(prefix = "embabel.presentation-maker")
data class PresentationMakerProperties(
    val researchLlm: String = OpenAiModels.GPT_41_MINI,
    val creationLlm: String = AnthropicModels.CLAUDE_37_SONNET,
)


/**
 * Naming agent that generates names for a company or project.
 */
@Agent(description = "Presentation maker. Build a presentation on a topic")
class PresentationMaker(
    private val slideFormatter: SlideFormatter,
    private val filePersister: FilePersister,
    private val properties: PresentationMakerProperties,
) {

    private val logger = LoggerFactory.getLogger(PresentationMaker::class.java)

    @Action
    fun identifyResearchTopics(presentationRequest: PresentationRequest): ResearchTopics =
        usingModel(
            properties.creationLlm,
//            toolGroups = setOf(CoreToolGroups.WEB),
        ).create(
            """
                Create a list of research topics for a presentation,
                based on the given input:
                ${presentationRequest.brief}
                About the presenter: ${presentationRequest.presenterBio}
                """.trimIndent()
        )

    @Action
    fun researchTopics(
        researchTopics: ResearchTopics,
        presentationRequest: PresentationRequest,
        context: OperationContext,
    ): ResearchResult {
        val researchReports = researchTopics.topics.parallelMap(context) {
            context.promptRunner(
                llm = LlmOptions.fromModel(properties.researchLlm),
                toolGroups = setOf(CoreToolGroups.WEB),
            )
                .withToolObject(presentationRequest.project)
                .withPromptContributor(presentationRequest)
                .create<ResearchReport>(
                    """
            Given the following topic and the goal to create a presentation
            for this audience, create a research report.
            Use web tools to research and the findPatternInProject tool to look
            within the given software project.
            Always look for code examples in the project before using the web.
            Topic: ${it.topic}
            Questions:
            ${it.questions.joinToString("\n")}
                """.trimIndent()
                )
        }
        return ResearchResult(
            topicResearches = researchTopics.topics.mapIndexed { index, topic ->
                CompletedResearch(
                    topic = topic,
                    researchReport = researchReports[index],
                )
            }
        )
    }

    @Action
    fun createDeck(
        presentationRequest: PresentationRequest,
        researchComplete: ResearchResult,
        context: OperationContext,
    ): SlideDeck {
        val reports = researchComplete.topicResearches.map { it.researchReport }
        val slideDeck = context.promptRunner(llm = LlmOptions(byName(properties.creationLlm)))
            .withPromptContributor(presentationRequest)
            .withToolGroup(CoreToolGroups.WEB)
            .withToolObject(presentationRequest.project)
            .create<SlideDeck>(
                """
                Create content for an impactful slide deck based on the given research.
                Use the following input to guide the presentation:

                # About the presenter
                ${presentationRequest.presenterBio}

                # Presentation narrative
                ${presentationRequest.brief}

                Support your points using the following research:
                $reports

                The presentation should be ${presentationRequest.slideCount} slides long.
                It should have a compelling narrative and call to action.
                It should end with a list of reference links.
                Use the findPatternInProject tool and other file tools to find relevant content within the given software project
                if required and format code on slides.

                Use Marp format, creating Markdown that can be rendered as slides.
                If you need to look it up, see https://github.com/marp-team/marp/blob/main/website/docs/guide/directives.md

                If you include GraphViz dot diagrams, do NOT enclose them in ```
                DO start with dot e.g. "dot digraph..."

                Use the following images as suggested:
                ${
                    presentationRequest.images.map { "${it.key}: ${it.value.url} - use when: ${it.value.useWhen}" }
                        .joinToString("\n")
                }

                Use the following header elements to start the deck.
                Add further header elements if you wish.

                ```
                ${presentationRequest.header}
                ```
            """.trimIndent()
            )
        filePersister.saveFile(
            directory = presentationRequest.outputDirectory,
            fileName = presentationRequest.rawOutputFile(),
            content = slideDeck.deck,
        )
        return slideDeck
    }

    @Action(outputBinding = "withDiagrams", cost = 1.0)
    fun expandDigraphs(
        slideDeck: SlideDeck,
        presentationRequest: PresentationRequest,
    ): SlideDeck {
        val diagramExpander = DotCliDigraphExpander(
            directory = presentationRequest.outputDirectory,
        )
        val withDigraphs = slideDeck.expandDigraphs(diagramExpander)
        filePersister.saveFile(
            directory = presentationRequest.outputDirectory,
            fileName = presentationRequest.withDiagramsOutputFile(),
            content = withDigraphs.deck,
        )
        return slideDeck
    }

    @Action(outputBinding = "withDiagrams")
    fun loadWithDigraphs(
        presentationRequest: PresentationRequest,
    ): SlideDeck? {
        return filePersister.loadFile(
            directory = presentationRequest.outputDirectory,
            fileName = presentationRequest.withDiagramsOutputFile(),
        )?.let {
            SlideDeck(it)
        }
    }

    @Action(outputBinding = "withIllustrations")
    fun addIllustrations(
        @RequireNameMatch withDiagrams: SlideDeck,
        presentationRequest: PresentationRequest,
        context: OperationContext,
    ): SlideDeck {
        val deckWithIllustrations = if (!presentationRequest.autoIllustrate) {
            logger.info("Not auto illustrating")
            withDiagrams
        } else {
            logger.info("Asking LLM to add illustrations to this resource")

            val illustrator = context.promptRunner(
                llm = LlmOptions(byName(properties.researchLlm)).withTemperature(.3)
            ).withToolGroup(CoreToolGroups.WEB)
            val newSlides = withDiagrams.slides().map { slide ->
                val newContent = illustrator.generateText(
                    """
                Take the following slide in MARP format.
                Overall objective: ${presentationRequest.brief}

                If the slide contains an important point, try to add an image to it
                Check that the image is available.
                Don't make the image too big.
                Put the image on the right.
                Make no other changes.
                Do not perform any web research besides seeking images.
                Return nothing but the amended slide content (the content between <slide></slide>).
                Do not ask any questions.
                If you don't think an image is needed, return the slide unchanged.

                <slide>
                ${slide.content}
                </slide>
            """.trimIndent()

                )
                Slide(
                    number = slide.number,
                    content = newContent,
                )
            }
            var dwi = withDiagrams
            for (slide in newSlides) {
                dwi = dwi.replaceSlide(slide, slide.content)
            }
            dwi
        }

        logger.info(
            "Saving final MARP markdown to {}/{}",
            presentationRequest.outputDirectory,
            presentationRequest.outputFile,
        )
        filePersister.saveFile(
            directory = presentationRequest.outputDirectory,
            fileName = presentationRequest.outputFile,
            content = deckWithIllustrations.deck,
        )
        return withDiagrams
    }

    @AchievesGoal(
        description = "Create a presentation based on research reports",
    )
    @Action
    fun convertToSlides(
        presentationRequest: PresentationRequest,
        @RequireNameMatch withIllustrations: SlideDeck,
    ): FileArtifact {
        val htmlFile = slideFormatter.createHtmlSlides(
            directory = presentationRequest.outputDirectory,
            markdownFilename = presentationRequest.outputFile,
        )
        return FileArtifact(
            directory = presentationRequest.outputDirectory,
            outputFile = htmlFile,
        )
    }

}

class Project(override val root: String) : FileReadTools, SymbolSearch {

    override val fileContentTransformers: List<FileContentTransformer> = listOf(removeApacheLicenseHeader)
}

@ShellComponent("Presentation maker commands")
class PresentationMakerShell(
    private val agentPlatform: AgentPlatform,
    private val resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
) {
    @ShellMethod
    fun makePresentation(
        @ShellOption(
            defaultValue = "file:/Users/rjohnson/dev/embabel.com/embabel-agent/embabel-agent-api/src/main/kotlin/com/embabel/examples/dogfood/presentation/kotlinconf_presentation.yml",
        )
        file: String,
    ): String {
        val yamlReader = ObjectMapper(YAMLFactory()).registerKotlinModule()

        val presentationRequest = yamlReader.readValue(
            resourceLoader.getResource(file).getContentAsString(Charset.defaultCharset()),
            PresentationRequest::class.java,
        )

        val agentProcess = agentPlatform.runAgentWithInput(
            agent = agentPlatform.agents().single { it.name == "PresentationMaker" },
            input = presentationRequest,
            processOptions = ProcessOptions(verbosity = Verbosity(showPrompts = true)),
        )

        return formatProcessOutput(
            result = DynamicExecutionResult.fromProcessStatus(basis = presentationRequest, agentProcess = agentProcess),
            colorPalette = LumonColorPalette,
            objectMapper = objectMapper,
        ) + "\ndeck is at ${presentationRequest.outputDirectory}/${presentationRequest.outputFile}"
    }
}
