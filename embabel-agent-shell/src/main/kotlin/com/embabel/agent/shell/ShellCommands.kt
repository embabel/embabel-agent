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
package com.embabel.agent.shell

import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.common.autonomy.*
import com.embabel.agent.core.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.event.logging.LoggingPersonality
import com.embabel.agent.event.logging.personality.ColorPalette
import com.embabel.agent.rag.Ingester
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.chat.agent.*
import com.embabel.chat.agent.shell.TerminalServicesProcessWaitingHandler
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.TemplateRenderer
import com.embabel.common.util.bold
import com.embabel.common.util.color
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/**
 * Shell configuration for the shell module (duplicate of API's ShellConfig for independence)
 */
data class ShellConfig(
    val lineLength: Int = 140,
    val chat: ChatConfig = ChatConfig(),
)


/**
 * Main shell entry point
 */
@ShellComponent
class ShellCommands(
    private val autonomy: Autonomy,
    private val planLister: PlanLister,
    private val modelProvider: ModelProvider,
    private val terminalServices: TerminalServices,
    private val environment: ConfigurableEnvironment,
    private val objectMapper: ObjectMapper,
    private val colorPalette: ColorPalette,
    private val loggingPersonality: LoggingPersonality,
    private val ingester: Ingester,
    private val toolsStats: ToolsStats,
    private val templateRenderer: TemplateRenderer,
    private val context: ConfigurableApplicationContext,
    private val shellProperties: ShellProperties = ShellProperties(),
) {

    private val logger: Logger = loggingPersonality.logger

    private val agentPlatform = autonomy.agentPlatform

    private val agentProcesses = mutableListOf<AgentProcess>()

    private var blackboard: Blackboard? = null

    /**
     * Whether to look for any goal
     */
    private var openMode: Boolean = false

    private var defaultProcessOptions: ProcessOptions = ProcessOptions(
        verbosity = Verbosity(
            debug = false,
            showPrompts = false,
            showLlmResponses = false,
            showPlanning = true,
        )
    )

    @ShellMethod(value = "Clear blackboard")
    fun clear(): String {
        blackboard = null
        return "Blackboard cleared"
    }

    @ShellMethod(value = "Show recent agent process runs. This is what actually happened, not just what was planned.")
    fun runs(): String {
        val plans = agentProcesses.map {
            "[${it.id}] Goal: ${it.agent.goals.map { g -> g.name }}; usage - ${it.costInfoString(verbose = false)}\n\t\t" +
                    it.history.joinToString("\n\t\t") { it.infoString() }
        }
        return "Recent runs:\n\t${plans.joinToString("\n\t")}"
    }

    @ShellMethod(value = "List all active Spring profiles")
    fun profiles(): String {
        val profiles = environment.activeProfiles
        return "Active profiles: ${profiles.joinToString()}"
    }

    @ShellMethod("Chat")
    fun chat(): String {
        val processOptions = ProcessOptions(
            verbosity = Verbosity(
                debug = false,
                showPrompts = false,
                showLlmResponses = false,
                showPlanning = true,
            )
        )
        blackboard = processOptions.blackboard

        val chatSession = AgentPlatformChatSession(
            messageListener = { },
            autonomy = autonomy,
            planLister = planLister,
            processOptions = processOptions,
            goalChoiceApprover = if (shellProperties.chat.confirmGoals) terminalServices else GoalChoiceApprover.APPROVE_ALL,
            processWaitingHandler = TerminalServicesProcessWaitingHandler(terminalServices),
            chatConfig = shellProperties.chat,
            responseGenerator = if (shellProperties.chat.bindConversation) AgentResponseGenerator(
                agentPlatform = agentPlatform,
                agent = DefaultChatAgentBuilder(
                    persona = K9,
                    templateRenderer = templateRenderer,
                    llm = LlmOptions
                        .withModel(shellProperties.chat.model)
                        .withTemperature(null)
                ).build()
            ) else null,
        )
        return terminalServices.chat(chatSession, colorPalette)
    }

    @ShellMethod("List agents")
    fun agents(): String {
        val detail = "${"Agents:".bold()}\n${
            agentPlatform.agents()
                .joinToString(separator = "\n${"-".repeat(shellProperties.lineLength)}\n") {
                    it.infoString(verbose = true, indent = 1)
                }
        }"
        return detail + "\n\nTLDR;\n${agentPlatform.agents().joinToString("\n") { "${it.name}: ${it.description}" }}"
    }

    @ShellMethod("List actions")
    fun actions(): String {
        return "${"Actions:".bold()}\n${
            agentPlatform.actions
                .joinToString(separator = "\n") { it.infoString(verbose = true, indent = 1) }
        }"
    }

    @ShellMethod("List conditions")
    fun conditions(): String {
        return "${"Conditions:".bold()}\n${
            agentPlatform.conditions
                .joinToString(separator = "\n") { it.infoString(verbose = true, indent = 1) }
        }"
    }

    @ShellMethod("List goals")
    fun goals(): String {
        return "${"Goals:".bold()}\n${
            agentPlatform.goals
                .joinToString(separator = "\n") { it.infoString(verbose = true, indent = 1) }
        }"
    }

    @ShellMethod("Try to choose a goal for a given intent. Show all goal rankings")
    fun chooseGoal(
        @ShellOption(help = "what the agent system should do") intent: String,
    ): String {
        try {
            val goalSeeker = autonomy.createGoalSeeker(
                intent = intent,
                agentScope = agentPlatform,
                goalChoiceApprover = GoalChoiceApprover approveWithScoreOver .8,
                goalSelectionOptions = GoalSelectionOptions(),
            )
            val fmt = goalSeeker.rankings.rankings().joinToString("\n") {
                it.infoString(verbose = true)
            }
            return fmt.color(colorPalette.color2) + "\n" + goalSeeker.agent.infoString(verbose = true)
        } catch (gna: GoalNotApproved) {
            return "Goal not approved. Rankings were:\n${gna.goalRankings.infoString(verbose = true)}"
        } catch (ngf: NoGoalFound) {
            return "No goal found. Rankings were:\n${ngf.goalRankings.infoString(verbose = true)}"
        }
    }

    @ShellMethod("Information about the AgentPlatform")
    fun platform(): String = "AgentPlatform: ${agentPlatform.name}"


    @ShellMethod(
        "Show last blackboard: The final state of a previous operation",
        key = ["blackboard", "bb"],
    )
    fun blackboard(): String {
        return if (blackboard == null) {
            "No blackboard available. Please run a command first."
        } else blackboard!!.infoString(verbose = true)
    }

    @ShellMethod("List available tool groups")
    fun tools(): String {
        val tgr = agentPlatform.toolGroupResolver
        return String.format(
            "%s: %s: %d available tool groups: %s",
            tgr.javaClass.name,
            tgr.name,
            tgr.availableToolGroups().size,
            "\n\t" + tgr.availableToolGroups()
                .map { tgr.resolveToolGroup(ToolGroupRequirement(it.role)) }
                .mapNotNull { it.resolvedToolGroup }
                .sortedBy { it.metadata.role }
                .joinToString("\n\t") { it.infoString(verbose = true) },
        )
    }

    @ShellMethod("Show tool stats")
    fun toolStats(): String {
        return toolsStats.infoString(verbose = true)
    }

    @ShellMethod("List available models")
    fun models(): String =
        modelProvider.infoString(true)

    @ShellMethod("List available rag services")
    fun ragService(): String =
        autonomy.agentPlatform.platformServices.ragService.infoString(verbose = true)

    @ShellMethod("ingest")
    fun ingest(
        @ShellOption(
            value = ["-u", "--u"],
            help = "File to ingest. Spring resource path or URL",
        ) url: String,
    ): String {
        if (!ingester.active()) {
            return "Cannot ingest: ${ingester.infoString(verbose = true)}"
        }
        return try {
            val ingestionResult = ingester.ingest(url)
            if (ingestionResult.success()) {
                "Ingested $url as ${ingestionResult.documentsWritten} documents to ${ingestionResult.storesWrittenTo} stores"
            } else {
                "Could not process ingestion."
            }
        } catch (e: Exception) {
            logger.error("Failed to ingest $url: ${e.message}", e)
            "Failed to ingest $url: ${e.message}"
        }
    }

    @ShellMethod("Show options")
    fun showOptions(): String {
        // Don't show the blackboard as it's long
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            defaultProcessOptions.copy(blackboard = null)
        ).replace(
            """
            "blackboard" : null
        """.trimIndent(), """
            "blackboard" : <${blackboard?.let { "${it.objects.size} entries" } ?: "empty"}>
        """.trimIndent())
            .color(colorPalette.color2)
    }

    @ShellMethod(
        "Set options",
    )
    fun setOptions(
        @ShellOption(
            value = ["-o", "--open"],
            help = "run in open mode, choosing a goal and using all actions that can help achieve it",
        ) open: Boolean = false,
        @ShellOption(value = ["-t", "--test"], help = "run in help mode") test: Boolean = false,
        @ShellOption(value = ["-p", "--showPrompts"], help = "show prompts to LLMs") showPrompts: Boolean,
        @ShellOption(value = ["-r", "--showResponses"], help = "show LLM responses") showLlmResponses: Boolean = false,
        @ShellOption(value = ["-d", "--debug"], help = "show debug info") debug: Boolean = false,
        @ShellOption(value = ["-s", "--state"], help = "Use existing blackboard") state: Boolean = false,
        @ShellOption(value = ["-td", "--toolDelay"], help = "Tool delay") toolDelay: Boolean = false,
        @ShellOption(value = ["-od", "--operationDelay"], help = "Operation delay") operationDelay: Boolean = false,
        @ShellOption(
            value = ["-s", "--showPlanning"],
            help = "show detailed planning info",
            defaultValue = "true",
        ) showPlanning: Boolean = true,
    ): String {
        this.openMode = open
        val verbosity = Verbosity(
            debug = debug,
            showPrompts = showPrompts,
            showLlmResponses = showLlmResponses,
            showPlanning = showPlanning,
        )
        this.defaultProcessOptions = ProcessOptions(
            test = test,
            blackboard = if (state) blackboard else null,
            verbosity = verbosity,
            allowGoalChange = true,
            control = ProcessControl(
                earlyTerminationPolicy = EarlyTerminationPolicy.maxActions(40),
                toolDelay = if (toolDelay) Delay.LONG else Delay.NONE,
                operationDelay = if (operationDelay) Delay.MEDIUM else Delay.NONE,
            )
        )
        return "Options updated:\nOpen mode:$openMode\n${showOptions()}".color(colorPalette.color2)
    }

    @ShellMethod(
        "Execute a task. Put the task in double quotes. For example:\n\tx \"Lynda is a scorpio. Find news for her\" -p",
        key = ["execute", "x"],
    )
    fun execute(
        @ShellOption(help = "what the agent system should do") intent: String,
        @ShellOption(
            value = ["-o", "--open"],
            help = "run in open mode, choosing a goal and using all actions that can help achieve it",
        ) open: Boolean = false,
        @ShellOption(value = ["-t", "--test"], help = "run in help mode") test: Boolean = false,
        @ShellOption(value = ["-p", "--showPrompts"], help = "show prompts to LLMs") showPrompts: Boolean,
        @ShellOption(value = ["-r", "--showResponses"], help = "show LLM responses") showLlmResponses: Boolean = false,
        @ShellOption(value = ["-d", "--debug"], help = "show debug info") debug: Boolean = false,
        @ShellOption(value = ["-s", "--state"], help = "Use existing blackboard") state: Boolean = false,
        @ShellOption(value = ["-td", "--toolDelay"], help = "Tool delay") toolDelay: Boolean = false,
        @ShellOption(value = ["-od", "--operationDelay"], help = "Operation delay") operationDelay: Boolean = false,
        @ShellOption(
            value = ["-P", "--showPlanning"],
            help = "show detailed planning info",
            defaultValue = "true",
        ) showPlanning: Boolean = true,
    ): String {
        // Override any options
        setOptions(
            open = open,
            test = test,
            showPrompts = showPrompts,
            showLlmResponses = showLlmResponses,
            debug = debug,
            state = state,
            toolDelay = toolDelay,
            operationDelay = operationDelay,
            showPlanning = showPlanning,
        )
        return executeIntent(
            intent = intent,
            processOptions = defaultProcessOptions,
        )
    }

    @ShellMethod(value = "Exit the application", key = ["exit", "quit", "bye"])
    fun exit(): String {
        println("Exiting...".color(colorPalette.color2))
        logger.info("Shutting down application...")

        // Perform any cleanup if needed
        try {
            // Clear any active processes
            agentProcesses.clear()
            // Graceful shutdown
            CompletableFuture.runAsync {
                Thread.sleep(100) // Small delay to let response print
                exitProcess(SpringApplication.exit(context, { 0 }))
            }
            return "Goodbye!".color(colorPalette.color2)
        } catch (e: Exception) {
            logger.warn("Error during shutdown: ${e.message}")
            return "Goodbye! (with errors)".color(colorPalette.color2)
        }
    }

    private fun executeIntent(
        processOptions: ProcessOptions,
        intent: String,
    ): String {
        val opt = if (processOptions.verbosity.debug) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(processOptions)
        } else {
            objectMapper.writeValueAsString(processOptions)
        }
        logger.info(
            "Created process options: $opt".color(colorPalette.highlight)
        )

        return runProcess(verbosity = processOptions.verbosity, basis = intent) {
            if (openMode) {
                logger.info("Executing in open mode: Trying to find appropriate goal and using all actions known to platform that can help achieve it")
                autonomy.chooseAndAccomplishGoal(
                    processOptions = processOptions,
                    goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
                    agentScope = agentPlatform,
                    bindings = mapOf(IoBinding.DEFAULT_BINDING to UserInput(intent)),
                    goalSelectionOptions = GoalSelectionOptions(),
                )
            } else {
                logger.info("Executing in closed mode: Trying to find appropriate agent")
                autonomy.chooseAndRunAgent(
                    intent = intent,
                    processOptions = processOptions
                )
            }
        }

    }

    private fun recordAgentProcess(agentProcess: AgentProcess) {
        agentProcesses.add(agentProcess)
        blackboard = agentProcess.processContext.blackboard
    }

    private fun runProcess(
        verbosity: Verbosity,
        basis: Any,
        run: () -> AgentProcessExecution,
    ): String {
        try {
            val result = run()
            logger.debug("Result: {}\n", result)
            recordAgentProcess(result.agentProcess)
            return formatProcessOutput(result, colorPalette, objectMapper, shellProperties.lineLength)
        } catch (ngf: NoGoalFound) {
            if (verbosity.debug) {
                logger.info(
                    """
                    Failed to choose goal:
                        Rankings were: [${ngf.goalRankings.infoString()}]
                        Cutoff was ${autonomy.properties.goalConfidenceCutOff}
                    """.trimIndent().color(0xbfb8b8)
                )
            }
            return "I'm sorry. I don't know how to do that.\n"
        } catch (gna: GoalNotApproved) {
            if (verbosity.debug) {
                logger.info(
                    """
                    Goal not approved:
                        Rankings were: [${gna.goalRankings.infoString()}]
                    """.trimIndent().color(0xbfb8b8)
                )
            }
            return "I'm sorry. I don't know how to do that.\n"
        } catch (naf: NoAgentFound) {
            if (verbosity.debug) {
                logger.info(
                    """
                    Failed to choose agent:
                        Rankings were: [${naf.agentRankings.infoString()}]
                        Cutoff was ${autonomy.properties.agentConfidenceCutOff}
                    """.trimIndent().color(0xbfb8b8)
                )
            }
            return "I'm sorry. I don't know how to do that.\n"
        } catch (pese: ProcessExecutionStuckException) {
            pese.agentProcess?.let {
                recordAgentProcess(it)
            }
            return "I'm sorry. I don't know how to proceed.\n"
        } catch (pete: ProcessExecutionTerminatedException) {
            pete.agentProcess?.let {
                recordAgentProcess(it)
            }
            return "The process was terminated. Not my fault.\n\t${pete.detail.color(colorPalette.color2)}\n"
        } catch (pwe: ProcessWaitingException) {
            recordAgentProcess(pwe.agentProcess)
            val awaitableResponse = terminalServices.handleProcessWaitingException(pwe)
            if (awaitableResponse == null) {
                return "Operation cancelled.\n"
            }
            pwe.awaitable.onResponse(
                response = awaitableResponse,
                agentProcess = pwe.agentProcess,
            )
            return runProcess(verbosity, basis) {
                AgentProcessExecution.fromProcessStatus(
                    basis = basis,
                    agentProcess = pwe.agentProcess.run()
                )
            }
        }
    }

}
