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
package com.embabel.agent.e2e

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.supervisor.Ingredient
import com.embabel.agent.api.annotation.support.supervisor.SupervisorWith3Steps
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.createObject
import com.embabel.agent.api.dsl.MagicVictim
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.Subagent
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Export
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ToolNamingStrategy
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.tools.agent.CONFIRMATION_TOOL_NAME
import com.embabel.agent.tools.agent.PerGoalToolFactory
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.embabel.agent.core.Agent as CoreAgent

class SearchTools {

    @LlmTool(description = "Search for something")
    fun search(query: String): String = "found $query"
}

data class Searched(val summary: String)

const val WEATHER_ROLE = "weather-role"

class WeatherTools {

    @LlmTool(description = "Report the weather")
    fun forecast(city: String): String = "sunny in $city"
}

class LocalWeatherTools {

    @LlmTool(description = "Report the weather from a local source")
    fun forecast(city: String): String = "local forecast for $city"
}

/**
 * Names a required tool in the group. The requirement is stated in the tool's own name, so it can
 * only be satisfied if requirements are checked before names are qualified.
 */
@Agent(description = "Requires a named tool from a group")
class RequiredToolNameAgent {

    @AchievesGoal(description = "Reported the weather")
    @Action
    fun report(
        input: UserInput,
        context: ActionContext,
    ): Searched = context.promptRunner()
        .withToolGroup(WEATHER_ROLE, "forecast")
        .createObject("Report for ${input.content}")
}

/** Publishes `forecast` twice under one owner: once from a tool object, once from the group. */
@Agent(description = "Publishes the same tool name from two sources")
class DuplicateToolNameAgent {

    @AchievesGoal(description = "Reported the weather")
    @Action
    fun report(
        input: UserInput,
        context: ActionContext,
    ): Searched = context.promptRunner()
        .withToolObject(LocalWeatherTools())
        .withToolGroup(WEATHER_ROLE)
        .createObject("Report for ${input.content}")
}

@Agent(description = "Publishes a tool group from one of its actions")
class ToolGroupUsingAgent {

    @AchievesGoal(description = "Reported the weather")
    @Action
    fun report(
        input: UserInput,
        context: ActionContext,
    ): Searched = context.promptRunner()
        .withToolGroup(WEATHER_ROLE)
        .createObject("Report for ${input.content}")
}

@Agent(description = "Publishes an object tool from one of its actions")
class ToolPublishingAgent {

    @AchievesGoal(description = "Searched for something")
    @Action
    fun searchFor(
        input: UserInput,
        context: ActionContext,
    ): Searched = context.promptRunner()
        .withToolObject(SearchTools())
        .createObject("Search for ${input.content}")
}

/**
 * Deliberately carries no stereotype annotation. A `@TestConfiguration` in this package is
 * component scanned into every context in `e2e`, where a primary [LlmOperations] would displace
 * the fake those tests rely on. As a plain class it is registered only where `@Import` names it.
 *
 * Wires a real [AbstractLlmOperations][com.embabel.agent.spi.support.AbstractLlmOperations] so that
 * tool resolution, naming, decoration and [LlmRequestEvent] emission run as in production.
 */
class RealPipelineConfig {

    @Bean
    @Primary
    fun realPipelineLlmOperations(): LlmOperations = RealPipelineLlmOperations()

    @Bean
    fun weatherToolGroup(): ToolGroup = ToolGroup(
        metadata = ToolGroupMetadata(
            description = ToolGroupDescription(description = "Weather", role = WEATHER_ROLE),
            name = "weather",
            provider = "test",
            permissions = setOf(ToolGroupPermission.INTERNET_ACCESS),
        ),
        tools = safelyGetToolsFrom(ToolObject(WeatherTools())),
    )
}

/**
 * The DSL route to publishing tools: `promptedTransformer` takes them directly, so no annotation
 * or reflection is involved. Its action name is whatever the builder was given.
 */
private fun dslToolAgent() = agent("DslToolAgent", description = "Reports the weather") {
    promptedTransformer<UserInput, Searched>(
        name = "dslReport",
        tools = safelyGetToolsFrom(ToolObject(WeatherTools())),
    ) { "Report for ${it.input.content}" }
    goal(
        name = "dslDone",
        description = "Reported the weather",
        satisfiedBy = Searched::class,
    )
}

/** A whole agent published as a single tool, named after the agent it runs. */
private fun dslSubagentAgent() = agent("DslSubagentAgent", description = "Delegates to a subagent") {
    promptedTransformer<UserInput, Searched>(
        name = "delegate",
        tools = listOf(Subagent.ofAnnotatedInstance(ToolPublishingAgent()).consuming(UserInput::class)),
    ) { "Delegate for ${it.input.content}" }
    goal(
        name = "delegated",
        description = "Delegated to a subagent",
        satisfiedBy = Searched::class,
    )
}

/** One agent, two goals sharing a name. `goals` is a `Set<Goal>` and `Goal` is a data class, so
 * two goals differing only in description are both retained. */
private fun agentWithTwoGoalsNamed(agentName: String, goalName: String) =
    agent(agentName, description = "Has two goals of one name") {
        transformation<UserInput, MagicVictim>(name = "$agentName-identify") { MagicVictim(agentName) }
        listOf("first", "second").forEach { which ->
            goal(
                name = goalName,
                description = "$agentName $which",
                satisfiedBy = MagicVictim::class,
                export = Export(remote = true, startingInputTypes = setOf(UserInput::class.java)),
            )
        }
    }

private fun exportedAgent(agentName: String, goalName: String, exportName: String? = null) =
    agent(agentName, description = "Turns a victim into something") {
        transformation<UserInput, MagicVictim>(name = "$agentName-identify") { MagicVictim(agentName) }
        goal(
            name = goalName,
            description = "$agentName is done",
            satisfiedBy = MagicVictim::class,
            export = Export(
                remote = true,
                name = exportName,
                startingInputTypes = setOf(UserInput::class.java),
            ),
        )
    }

private class EventCollector : AgenticEventListener {

    private val events = ConcurrentLinkedQueue<AgentProcessEvent>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        events.add(event)
    }

    /** Every tool handed to the model, in publication order, across all LLM calls. */
    fun publishedTools(): List<Tool> = events
        .filterIsInstance<LlmRequestEvent<*>>()
        .flatMap { it.interaction.tools }

    fun publishedToolNames(): List<String> = publishedTools().map { it.definition.name }

    fun requests(): List<LlmRequestEvent<*>> = events.filterIsInstance<LlmRequestEvent<*>>()
}

/**
 * End to end coverage of tool naming under `FULLY_QUALIFIED`, observed where production publishes:
 * the tools carried by [LlmRequestEvent], and the tools a [PerGoalToolFactory] generates from a
 * live platform.
 *
 * Deliberately not covered here, and left to the unit tests named beside each entry, because an
 * end to end version needs a fake model that emits real tool calls - a great deal of machinery for
 * a path the unit tests already pin:
 *  - streaming (`ToolResolutionHelperTest`): reaching `StreamingLlmOperationsImpl` requires a
 *    streaming message sender producing NDJSON chunks.
 *  - tools injected mid loop by an unfolding tool
 *    (`AbstractLlmOperationsToolNamingTest.InjectedTools`): requires the model to ask for a tool,
 *    then a second turn.
 *  - the MCP publishers (`PerGoalMcpExportToolCallbackPublisherTest`): they live in
 *    embabel-agent-mcpserver, which this module cannot start a Spring context for.
 *    `McpServerProtocolIntegrationTest` there covers the protocol itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["embabel.agent.platform.tools.naming-strategy=fully-qualified"])
@Import(RealPipelineConfig::class)
class ToolNamingIntegrationTest(
    @param:Autowired private val autonomy: Autonomy,
) {


    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    private fun runCollecting(
        agent: CoreAgent,
        plannerType: PlannerType = PlannerType.GOAP,
        bindings: Map<String, Any> = mapOf("it" to UserInput("frogs")),
    ): EventCollector {
        val collector = EventCollector()
        agentPlatform.runAgentFrom(
            agent,
            ProcessOptions(listeners = listOf(collector), plannerType = plannerType),
            bindings,
        )
        return collector
    }

    private fun metadataFor(instance: Any): CoreAgent =
        AgentMetadataReader().createAgentMetadata(instance) as CoreAgent

    private fun toolFactory() = PerGoalToolFactory(
        autonomy = autonomy,
        applicationName = "test-app",
        textCommunicator = PromptedTextCommunicator,
        toolNamingStrategy = agentPlatform.platformServices.toolNamingStrategy(),
    )

    private fun goalToolNames(): List<String> =
        toolFactory().goalTools(remoteOnly = true, listeners = emptyList()).map { it.definition.name }

    @Nested
    inner class PlatformConfiguration {

        @Test
        fun `the configured strategy reaches platform services`() {
            assertEquals(ToolNamingStrategy.FULLY_QUALIFIED, agentPlatform.platformServices.toolNamingStrategy())
        }
    }

    @Nested
    inner class ActionToolNames {

        @Test
        fun `qualifies an object tool with the owning agent and action`() {
            val names = runCollecting(metadataFor(ToolPublishingAgent())).publishedToolNames()

            assertTrue(names.contains("ToolPublishingAgent-search"), names.toString())
            assertFalse(names.contains("search"), names.toString())
        }

        /**
         * A caller only ever has the published name. Looking a tool up by it, as the tool loop
         * does, must reach the function the name was derived from.
         */
        @Test
        fun `calling a tool by its published name reaches the underlying function`() {
            val tools = runCollecting(metadataFor(ToolPublishingAgent())).publishedTools()

            val tool = tools.single { it.definition.name == "ToolPublishingAgent-search" }
            val result = tool.call("""{"query": "frogs"}""")

            assertEquals("found frogs", (result as Tool.Result.Text).content)
        }

        @Test
        fun `qualifies a tool published by a DSL agent`() {
            val names = runCollecting(dslToolAgent()).publishedToolNames()

            assertTrue(names.contains("DslToolAgent-forecast"), names.toString())
            assertFalse(names.contains("forecast"), names.toString())
        }

        @Test
        fun `qualifies a subagent published as a tool`() {
            val names = runCollecting(dslSubagentAgent()).publishedToolNames()

            assertTrue(names.contains("DslSubagentAgent-ToolPublishingAgent"), names.toString())
        }

        /**
         * Both routes run against one platform in one test. Each publication is qualified by the
         * agent and action that owns it, so the same underlying function reaches the model under
         * two different names and neither route leaks its owner into the other.
         */
        @Test
        fun `qualifies DSL and annotation agents independently in the same platform`() {
            val fromDsl = runCollecting(dslToolAgent()).publishedToolNames()
            val fromAnnotation = runCollecting(metadataFor(ToolGroupUsingAgent())).publishedToolNames()

            assertTrue(fromDsl.contains("DslToolAgent-forecast"), fromDsl.toString())
            assertTrue(fromAnnotation.contains("ToolGroupUsingAgent-forecast"), fromAnnotation.toString())
            assertFalse(fromDsl.any { it.startsWith("ToolGroupUsingAgent") }, fromDsl.toString())
            assertFalse(fromAnnotation.any { it.startsWith("DslToolAgent") }, fromAnnotation.toString())
        }

        @Test
        fun `qualifies a tool resolved from a tool group`() {
            val names = runCollecting(metadataFor(ToolGroupUsingAgent())).publishedToolNames()

            assertTrue(names.contains("ToolGroupUsingAgent-forecast"), names.toString())
            assertFalse(names.contains("forecast"), names.toString())
        }

        /**
         * Requirements are stated in unqualified names, so they must be checked before the
         * strategy renames anything. If that order ever inverts the process fails to resolve
         * its tools rather than publishing them.
         */
        @Test
        fun `satisfies a required tool name stated before qualification`() {
            val names = runCollecting(metadataFor(RequiredToolNameAgent())).publishedToolNames()

            assertTrue(names.contains("RequiredToolNameAgent-forecast"), names.toString())
        }

        /**
         * Known limitation, recorded rather than hidden. Qualification uses one owner per
         * publication, so two sources contributing the same simple name still collapse to a single
         * published name and one tool is dropped without a warning. Per-tool owners would
         * distinguish them; that mechanism was removed as redundant, which it is everywhere except
         * here.
         */
        @Test
        fun `drops one of two tools that share a simple name under the same owner`() {
            val names = runCollecting(metadataFor(DuplicateToolNameAgent())).publishedToolNames()

            assertEquals(
                1,
                names.count { it == "DuplicateToolNameAgent-forecast" },
                names.toString(),
            )
        }

        @Test
        fun `calling a tool group tool by its published name reaches the underlying function`() {
            val tools = runCollecting(metadataFor(ToolGroupUsingAgent())).publishedTools()

            val tool = tools.single { it.definition.name == "ToolGroupUsingAgent-forecast" }
            val result = tool.call("""{"city": "Hanoi"}""")

            assertEquals("sunny in Hanoi", (result as Tool.Result.Text).content)
        }
    }

    @Nested
    inner class SupervisorToolNames {

        @Test
        fun `qualifies curried supervisor tools under the supervisor action`() {
            val names = runCollecting(
                metadataFor(SupervisorWith3Steps()),
                PlannerType.SUPERVISOR,
                mapOf("ingredient" to Ingredient("flour")),
            ).publishedToolNames()

            assertTrue(names.contains("SupervisorWith3Steps-bakeBread"), names.toString())
            assertTrue(names.contains("SupervisorWith3Steps-makeDough"), names.toString())
        }

        /**
         * The prompt names the tools the model may call, so it has to agree with the names actually
         * published in the same request. They agree only because the supervisor action is named
         * `<agent>.supervisor`, from which the boundary derives the same owner; nothing in the code
         * states that, so it is asserted here.
         */
        @Test
        fun `lists exactly the published tool names in the prompt of the same request`() {
            val requests = runCollecting(
                metadataFor(SupervisorWith3Steps()),
                PlannerType.SUPERVISOR,
                mapOf("ingredient" to Ingredient("flour")),
            ).requests()

            val supervisorRequests = requests.filter { it.interaction.tools.isNotEmpty() }
            assertTrue(supervisorRequests.isNotEmpty(), "no request carried tools")
            supervisorRequests.forEach { request ->
                val prompt = request.messages.joinToString("\n") { it.content }
                request.interaction.tools.forEach { tool ->
                    assertTrue(
                        prompt.contains(tool.definition.name),
                        "prompt omits ${tool.definition.name}: $prompt",
                    )
                }
            }
        }
    }

    /**
     * Collisions the strategy does not resolve. Each test states the duplicate it produces and is
     * disabled because it fails today: nothing in `PerGoalToolFactory` deduplicates or reports, so
     * an MCP client is offered two tools of one name and the second silently shadows the first.
     * Enable each when the corresponding collision is handled.
     */
    @Nested
    inner class KnownDuplicates {

        /**
         * An explicit `export.name` is published verbatim, by design, so qualification cannot
         * separate two agents that chose the same one. This is the one case where opting out of
         * generated names also opts out of the collision fix.
         */
        @Disabled("Known collision: explicit export names bypass qualification and are not deduplicated")
        @Test
        fun `two agents choosing one explicit export name publish it twice`() {
            agentPlatform.deploy(exportedAgent("HippoWizard", goalName = "hippo", exportName = "shared.export"))
            agentPlatform.deploy(exportedAgent("IbisWizard", goalName = "ibis", exportName = "shared.export"))

            val names = goalToolNames()

            assertEquals(names.size, names.toSet().size, names.toString())
        }

        /**
         * `allTools` concatenates goal tools and platform tools without checking for overlap, so a
         * goal that exports under a reserved framework name shadows the framework tool.
         */
        @Disabled("Known collision: goal tools and platform tools are concatenated without deduplication")
        @Test
        fun `a goal exporting under a reserved framework name publishes it twice`() {
            agentPlatform.deploy(
                exportedAgent("JackalWizard", goalName = "jackal", exportName = CONFIRMATION_TOOL_NAME),
            )

            val names = toolFactory().allTools(remoteOnly = true, listeners = emptyList())
                .map { it.definition.name }

            assertEquals(names.size, names.toSet().size, names.toString())
        }

        /**
         * Qualification uses the agent name, so two goals of one name inside a single agent
         * qualify to the same published name.
         */
        @Disabled("Known collision: goals sharing a name within one agent qualify identically")
        @Test
        fun `two goals of one name in a single agent publish the same tool twice`() {
            agentPlatform.deploy(agentWithTwoGoalsNamed("KoalaWizard", goalName = "koala"))

            val names = goalToolNames()

            assertEquals(names.size, names.toSet().size, names.toString())
        }
    }

    @Nested
    inner class GoalToolNames {

        @Test
        fun `qualifies a generated goal tool with its owning agent`() {
            agentPlatform.deploy(exportedAgent("AardvarkWizard", goalName = "done"))

            assertTrue(goalToolNames().contains("AardvarkWizard-done"), goalToolNames().toString())
        }

        @Test
        fun `publishes both goals when two agents share a goal name`() {
            agentPlatform.deploy(exportedAgent("BadgerWizard", goalName = "shared"))
            agentPlatform.deploy(exportedAgent("CamelWizard", goalName = "shared"))

            val names = goalToolNames()

            assertTrue(names.contains("BadgerWizard-shared"), names.toString())
            assertTrue(names.contains("CamelWizard-shared"), names.toString())
        }

        @Test
        fun `leaves an explicit export name untouched`() {
            agentPlatform.deploy(exportedAgent("DingoWizard", goalName = "dingoGoal", exportName = "chosen.name"))

            assertTrue(goalToolNames().contains("chosen.name"), goalToolNames().toString())
        }

        @Test
        fun `keeps reserved names for framework tools published beside goal tools`() {
            agentPlatform.deploy(exportedAgent("EmuWizard", goalName = "emuGoal"))

            val factory = PerGoalToolFactory(
                autonomy = autonomy,
                applicationName = "test-app",
                textCommunicator = PromptedTextCommunicator,
                toolNamingStrategy = agentPlatform.platformServices.toolNamingStrategy(),
            )

            assertTrue(factory.platformTools.map { it.definition.name }.contains(CONFIRMATION_TOOL_NAME))
        }
    }
}

/**
 * The same boundaries under the default strategy, proving the published names are unchanged.
 * A separate class because the strategy is a Spring property and therefore fixed per context.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["embabel.agent.platform.tools.naming-strategy=legacy-name-only"])
@Import(RealPipelineConfig::class)
class LegacyToolNamingIntegrationTest(
    @param:Autowired private val autonomy: Autonomy,
) {


    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    private fun collect(instance: Any): EventCollector {
        val collector = EventCollector()
        agentPlatform.runAgentFrom(
            AgentMetadataReader().createAgentMetadata(instance) as CoreAgent,
            ProcessOptions(listeners = listOf(collector)),
            mapOf("it" to UserInput("frogs")),
        )
        return collector
    }

    @Nested
    inner class PlatformConfiguration {

        @Test
        fun `defaults to legacy naming`() {
            assertEquals(ToolNamingStrategy.LEGACY_NAME_ONLY, agentPlatform.platformServices.toolNamingStrategy())
        }
    }

    @Nested
    inner class ActionToolNames {

        @Test
        fun `publishes an object tool under its own name`() {
            val names = collect(ToolPublishingAgent()).publishedToolNames()

            assertTrue(names.contains("search"), names.toString())
        }

        @Test
        fun `publishes a tool group tool under its own name`() {
            val names = collect(ToolGroupUsingAgent()).publishedToolNames()

            assertTrue(names.contains("forecast"), names.toString())
        }

        @Test
        fun `publishes a DSL agent tool under its own name`() {
            val collector = EventCollector()
            agentPlatform.runAgentFrom(
                dslToolAgent(),
                ProcessOptions(listeners = listOf(collector)),
                mapOf("it" to UserInput("frogs")),
            )

            assertTrue(collector.publishedToolNames().contains("forecast"), collector.publishedToolNames().toString())
        }

        @Test
        fun `calling a tool by its published name reaches the underlying function`() {
            val tool = collect(ToolPublishingAgent()).publishedTools().single { it.definition.name == "search" }

            val result = tool.call("""{"query": "frogs"}""")

            assertEquals("found frogs", (result as Tool.Result.Text).content)
        }
    }

    @Nested
    inner class GoalToolNames {

        @Test
        fun `publishes the application prefixed goal name`() {
            agentPlatform.deploy(exportedAgent("FerretWizard", goalName = "ferretGoal"))

            val names = PerGoalToolFactory(
                autonomy = autonomy,
                applicationName = "test-app",
                textCommunicator = PromptedTextCommunicator,
                toolNamingStrategy = agentPlatform.platformServices.toolNamingStrategy(),
            ).goalTools(remoteOnly = true, listeners = emptyList()).map { it.definition.name }

            assertTrue(names.contains("test_app_ferretGoal"), names.toString())
        }
    }
}

/**
 * No `tools.naming-strategy` property at all. The other two classes set it explicitly, so neither
 * exercises the path a real deployment takes before anyone configures anything.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RealPipelineConfig::class)
class UnconfiguredToolNamingIntegrationTest(
    @param:Autowired private val autonomy: Autonomy,
) {

    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    @Nested
    inner class PlatformConfiguration {

        @Test
        fun `falls back to legacy naming when the property is absent`() {
            assertEquals(ToolNamingStrategy.LEGACY_NAME_ONLY, agentPlatform.platformServices.toolNamingStrategy())
        }
    }

    @Nested
    inner class ActionToolNames {

        @Test
        fun `publishes tool names unchanged`() {
            val collector = EventCollector()
            agentPlatform.runAgentFrom(
                AgentMetadataReader().createAgentMetadata(ToolPublishingAgent()) as CoreAgent,
                ProcessOptions(listeners = listOf(collector)),
                mapOf("it" to UserInput("frogs")),
            )

            assertTrue(collector.publishedToolNames().contains("search"), collector.publishedToolNames().toString())
        }
    }
}
