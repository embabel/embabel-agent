[[reference.testing]]
=== Testing

Like Spring, Embabel facilitates testing of user applications.
The framework provides comprehensive testing support for both unit and integration testing scenarios.

IMPORTANT: Building Gen AI applications is no different from building other software.
Testing is critical to delivering quality software and must be considered from the outset.

==== Unit Testing

Unit testing in Embabel enables testing individual agent actions without involving real LLM calls.

Embabel's design means that agents are usually POJOs that can be instantiated with fake or mock objects.
Actions are methods that can be called directly with test fixtures.
In additional to your domain objects, you will pass a text fixture for the Embabel `OperationContext`, enabling you to intercept and verify LLM calls.

The framework provides `FakePromptRunner` and `FakeOperationContext` to mock LLM interactions while allowing you to verify prompts, hyperparameters, and business logic.
Alternatively you can use mock objects.
https://site.mockito.org/[Mockito] is the default choice for Java; https://mockk.io/[mockk] for Kotlin.

===== Testing Prompts and Hyperparameters

Here are unit tests from the http://github.com/embabel/java-agent-template[Java Agent Template] and http://github.com/embabel/kotlin-agent-template[Kotlin Agent Template] repositories, using Embabel fake objects:

[tabs]
====
Java::
+
[source,java]
----
class WriteAndReviewAgentTest {

    @Test
    void testWriteAndReviewAgent() {
        var context = FakeOperationContext.create();
        var promptRunner = (FakePromptRunner) context.promptRunner();
        context.expectResponse(new Story("One upon a time Sir Galahad . . "));

        var agent = new WriteAndReviewAgent(200, 400);
        agent.craftStory(new UserInput("Tell me a story about a brave knight", Instant.now()), context);

        String prompt = promptRunner.getLlmInvocations().getFirst().getPrompt();
        assertTrue(prompt.contains("knight"), "Expected prompt to contain 'knight'");

        var temp = promptRunner.getLlmInvocations().getFirst().getInteraction().getLlm().getTemperature();
        assertEquals(0.9, temp, 0.01,
                "Expected temperature to be 0.9: Higher for more creative output");
    }

    @Test
    void testReview() {
        var agent = new WriteAndReviewAgent(200, 400);
        var userInput = new UserInput("Tell me a story about a brave knight", Instant.now());
        var story = new Story("Once upon a time, Sir Galahad...");
        var context = FakeOperationContext.create();
        context.expectResponse("A thrilling tale of bravery and adventure!");
        agent.reviewStory(userInput, story, context);

        var promptRunner = (FakePromptRunner) context.promptRunner();
        String prompt = promptRunner.getLlmInvocations().getFirst().getPrompt();
        assertTrue(prompt.contains("knight"), "Expected review prompt to contain 'knight'");
        assertTrue(prompt.contains("review"), "Expected review prompt to contain 'review'");
    }
}
----

Kotlin::
+
[source,kotlin]
----
/**
 * Unit tests for the WriteAndReviewAgent class.
 * Tests the agent's ability to craft and review stories based on user input.
 */
internal class WriteAndReviewAgentTest {

    /**
     * Tests the story crafting functionality of the WriteAndReviewAgent.
     * Verifies that the LLM call contains expected content and configuration.
     */
    @Test
    fun testCraftStory() {
        // Create agent with word limits: 200 min, 400 max
        val agent = WriteAndReviewAgent(200, 400)
        val context = FakeOperationContext.create()
        val promptRunner = context.promptRunner() as FakePromptRunner

        context.expectResponse(Story("One upon a time Sir Galahad . . "))

        agent.craftStory(
            UserInput("Tell me a story about a brave knight", Instant.now()),
            context
        )

        // Verify the prompt contains the expected keyword
        Assertions.assertTrue(
            promptRunner.llmInvocations.first().prompt.contains("knight"),
            "Expected prompt to contain 'knight'"
        )

        // Verify the temperature setting for creative output
        val actual = promptRunner.llmInvocations.first().interaction.llm.temperature
        Assertions.assertEquals(
            0.9, actual, 0.01,
            "Expected temperature to be 0.9: Higher for more creative output"
        )
    }

    @Test
    fun testReview() {
        val agent = WriteAndReviewAgent(200, 400)
        val userInput = UserInput("Tell me a story about a brave knight", Instant.now())
        val story = Story("Once upon a time, Sir Galahad...")
        val context = FakeOperationContext.create()

        context.expectResponse("A thrilling tale of bravery and adventure!")
        agent.reviewStory(userInput, story, context)

        val promptRunner = context.promptRunner() as FakePromptRunner
        val prompt = promptRunner.llmInvocations.first().prompt
        Assertions.assertTrue(prompt.contains("knight"), "Expected review prompt to contain 'knight'")
        Assertions.assertTrue(prompt.contains("review"), "Expected review prompt to contain 'review'")

        // Verify single LLM invocation during review
        Assertions.assertEquals(1, promptRunner.llmInvocations.size)
    }
}
----
====

===== Testing the Fluent API: withId() and creating()

The `FakePromptRunner` fully supports the fluent API patterns used in production code, enabling comprehensive unit testing of agents that use `withId()` for interaction tracing and `creating()` for structured object creation with examples.

**Testing withId() for Interaction Tracing:**

The `withId()` method sets an interaction ID for better log tracing. In tests, you can verify the interaction ID was correctly set:

[tabs]
====
Java::
+
[source,java]
----
@Test
void shouldSetInteractionIdCorrectly() {
    var context = FakeOperationContext.create();
    var expectedIntent = new UserIntent("command", "Change channel names");
    context.expectResponse(expectedIntent);

    var result = context.ai()
            .withId("classify-intent")  // Set interaction ID for tracing
            .creating(UserIntent.class)
            .fromPrompt("Classify the user's intent");

    assertEquals(expectedIntent, result);

    // Verify the interaction ID was set correctly
    var interaction = context.getLlmInvocations().getFirst().getInteraction();
    assertEquals("classify-intent", interaction.getId().getValue());
}
----

Kotlin::
+
[source,kotlin]
----
@Test
fun `should set interaction ID correctly`() {
    val context = FakeOperationContext.create()
    val expectedIntent = UserIntent("command", "Change channel names")
    context.expectResponse(expectedIntent)

    val result = context.ai()
        .withId("classify-intent")  // Set interaction ID for tracing
        .creating(UserIntent::class.java)
        .fromPrompt("Classify the user's intent")

    assertEquals(expectedIntent, result)

    // Verify the interaction ID was set correctly
    val interaction = context.llmInvocations.first().interaction
    assertEquals(InteractionId("classify-intent"), interaction.id)
}
----
====

**Testing creating() with withExample():**

The `creating()` API allows you to provide strongly-typed examples to improve LLM output quality. In tests, you can verify examples were included:

[tabs]
====
Java::
+
[source,java]
----
@Test
void shouldIncludeExamplesInPrompt() {
    var context = FakeOperationContext.create();
    var expectedPlan = new ChannelEditPlan(1, "Lead Vox");
    context.expectResponse(expectedPlan);

    var result = context.ai()
            .withLlm(llmSelectionService.selectOptimalLlm())
            .withId("analyze-edit-request")
            .creating(ChannelEditPlan.class)
            .withExample("Rename channel 1", new ChannelEditPlan(1, "Bass"))
            .withExample("Rename channel 2", new ChannelEditPlan(2, "Drums"))
            .fromPrompt("Analyze the edit request");

    assertEquals(expectedPlan, result);

    // Verify examples were added as prompt contributors
    var promptContributors = context.getLlmInvocations().getFirst()
            .getInteraction().getPromptContributors();
    assertTrue(promptContributors.size() >= 2, "Examples should be added as prompt contributors");
}
----

Kotlin::
+
[source,kotlin]
----
@Test
fun `should include examples in prompt`() {
    val context = FakeOperationContext.create()
    val expectedPlan = ChannelEditPlan(1, "Lead Vox")
    context.expectResponse(expectedPlan)

    val result = context.ai()
        .withLlm(llmSelectionService.selectOptimalLlm())
        .withId("analyze-edit-request")
        .creating(ChannelEditPlan::class.java)
        .withExample("Rename channel 1", ChannelEditPlan(1, "Bass"))
        .withExample("Rename channel 2", ChannelEditPlan(2, "Drums"))
        .fromPrompt("Analyze the edit request")

    assertEquals(expectedPlan, result)

    // Verify examples were added as prompt contributors
    val promptContributors = context.llmInvocations.first().interaction.promptContributors
    assertTrue(promptContributors.size >= 2, "Examples should be added as prompt contributors")
}
----
====

**Using CreationExample for Reusable Examples:**

For cleaner code and reusability, you can use the `CreationExample` data class to define examples that can be shared across tests or passed as collections:

[tabs]
====
Java::
+
[source,java]
----
@Test
void shouldUseCreationExampleDataClass() {
    var context = FakeOperationContext.create();
    var expectedPlan = new ChannelEditPlan(1, "Lead Vox");
    context.expectResponse(expectedPlan);

    // Create a reusable example using CreationExample
    var example = new CreationExample<>(
        "Rename channel example",
        new ChannelEditPlan(2, "Rhythm")
    );

    var result = context.ai()
            .withDefaultLlm()
            .creating(ChannelEditPlan.class)
            .withExample(example)  // Pass the CreationExample directly
            .fromPrompt("Analyze the edit request");

    assertEquals(expectedPlan, result);
}
----

Kotlin::
+
[source,kotlin]
----
@Test
fun `should use CreationExample data class`() {
    val context = FakeOperationContext.create()
    val expectedPlan = ChannelEditPlan(1, "Lead Vox")
    context.expectResponse(expectedPlan)

    // Create a reusable example using CreationExample
    val example = CreationExample(
        description = "Rename channel example",
        value = ChannelEditPlan(2, "Rhythm")
    )

    val result = context.ai()
        .withDefaultLlm()
        .creating(ChannelEditPlan::class.java)
        .withExample(example)  // Pass the CreationExample directly
        .fromPrompt("Analyze the edit request")

    assertEquals(expectedPlan, result)
}
----
====

**Adding Multiple Examples with withExamples():**

When you have many examples to add, use `withExamples()` to pass them as a list or vararg. This is especially useful when examples are loaded from a file or database:

[tabs]
====
Java::
+
[source,java]
----
@Test
void shouldAddMultipleExamplesFromList() {
    var context = FakeOperationContext.create();
    var expectedPlan = new ChannelEditPlan(1, "Lead Vox");
    context.expectResponse(expectedPlan);

    // Create a list of examples (could be loaded from configuration)
    var examples = List.of(
        new CreationExample<>("Rename to Bass", new ChannelEditPlan(1, "Bass")),
        new CreationExample<>("Rename to Drums", new ChannelEditPlan(2, "Drums")),
        new CreationExample<>("Rename to Keys", new ChannelEditPlan(3, "Keys")),
        new CreationExample<>("Rename to Vocals", new ChannelEditPlan(4, "Vocals"))
    );

    var result = context.ai()
            .withDefaultLlm()
            .creating(ChannelEditPlan.class)
            .withExamples(examples)  // Pass all examples at once
            .fromPrompt("Analyze the request");

    assertEquals(expectedPlan, result);

    // Verify all examples were added
    var promptContributors = context.getLlmInvocations().getFirst()
            .getInteraction().getPromptContributors();
    assertTrue(promptContributors.size() >= 4);
}
----

Kotlin::
+
[source,kotlin]
----
@Test
fun `should add multiple examples from list`() {
    val context = FakeOperationContext.create()
    val expectedPlan = ChannelEditPlan(1, "Lead Vox")
    context.expectResponse(expectedPlan)

    // Create a list of examples (could be loaded from configuration)
    val examples = listOf(
        CreationExample("Rename to Bass", ChannelEditPlan(1, "Bass")),
        CreationExample("Rename to Drums", ChannelEditPlan(2, "Drums")),
        CreationExample("Rename to Keys", ChannelEditPlan(3, "Keys")),
        CreationExample("Rename to Vocals", ChannelEditPlan(4, "Vocals"))
    )

    val result = context.ai()
        .withDefaultLlm()
        .creating(ChannelEditPlan::class.java)
        .withExamples(examples)  // Pass all examples at once
        .fromPrompt("Analyze the request")

    assertEquals(expectedPlan, result)

    // Verify all examples were added
    val promptContributors = context.llmInvocations.first().interaction.promptContributors
    assertTrue(promptContributors.size >= 4)
}
----
====

You can also use vararg syntax for inline example lists:

[tabs]
====
Java::
+
[source,java]
----
var result = context.ai()
        .withDefaultLlm()
        .creating(ChannelEditPlan.class)
        .withExamples(
            new CreationExample<>("Example 1", new ChannelEditPlan(1, "Bass")),
            new CreationExample<>("Example 2", new ChannelEditPlan(2, "Drums")),
            new CreationExample<>("Example 3", new ChannelEditPlan(3, "Keys"))
        )
        .fromPrompt("Analyze the request");
----

Kotlin::
+
[source,kotlin]
----
val result = context.ai()
    .withDefaultLlm()
    .creating(ChannelEditPlan::class.java)
    .withExamples(
        CreationExample("Example 1", ChannelEditPlan(1, "Bass")),
        CreationExample("Example 2", ChannelEditPlan(2, "Drums")),
        CreationExample("Example 3", ChannelEditPlan(3, "Keys"))
    )
    .fromPrompt("Analyze the request")
----
====

**Full Fluent API Chain Example:**

Here's a complete example showing how to test an action that uses all the fluent API features:

[tabs]
====
Java::
+
[source,java]
----
@Test
void shouldTestCompleteFluentApiChain() {
    var context = FakeOperationContext.create();
    var expectedOutput = new ComplexOutput("analysis complete", 42);
    context.expectResponse(expectedOutput);

    // Production code pattern with full fluent API chain
    var result = context.ai()
            .withLlm(LlmOptions.withModel("gpt-4"))
            .withId("complex-analysis")
            .withSystemPrompt("You are an expert analyst")
            .creating(ComplexOutput.class)
            .withExample("Simple case", new ComplexOutput("basic", 1))
            .withExample("Complex case", new ComplexOutput("advanced", 100))
            .fromPrompt("Analyze the input data");

    assertEquals(expectedOutput, result);

    // Comprehensive verification
    var invocation = context.getLlmInvocations().getFirst();
    assertEquals("gpt-4", invocation.getInteraction().getLlm().getModel());
    assertEquals("complex-analysis", invocation.getInteraction().getId().getValue());
    assertTrue(invocation.getInteraction().getPromptContributors().size() >= 3); // system + 2 examples
}
----

Kotlin::
+
[source,kotlin]
----
@Test
fun `should test complete fluent API chain`() {
    val context = FakeOperationContext.create()
    val expectedOutput = ComplexOutput("analysis complete", 42)
    context.expectResponse(expectedOutput)

    // Production code pattern with full fluent API chain
    val result = context.ai()
        .withLlm(LlmOptions.withModel("gpt-4"))
        .withId("complex-analysis")
        .withSystemPrompt("You are an expert analyst")
        .creating(ComplexOutput::class.java)
        .withExample("Simple case", ComplexOutput("basic", 1))
        .withExample("Complex case", ComplexOutput("advanced", 100))
        .fromPrompt("Analyze the input data")

    assertEquals(expectedOutput, result)

    // Comprehensive verification
    val invocation = context.llmInvocations.first()
    assertEquals("gpt-4", invocation.interaction.llm.model)
    assertEquals("complex-analysis", invocation.interaction.id.value)
    assertTrue(invocation.interaction.promptContributors.size >= 3) // system + 2 examples
}
----
====

===== Key Testing Patterns Demonstrated

**Testing Prompt Content:**

- Use `context.getLlmInvocations().getFirst().getPrompt()` to get the actual prompt sent to the LLM
- Verify that key domain data is properly included in the prompt using `assertTrue(prompt.contains(...))`

**Testing Tools:**

- Access tools via `getInteraction().getTools()` to verify tools added via `withToolObject()` or `withTool()`
- Access tool group requirements via `getInteraction().getToolGroups()` to verify named tool group requirements added via `withToolGroup(ToolGroupRequirement)`

NOTE: `getTools()` returns actual `Tool` instances (from `withToolObject()` and `withTool()`), while `getToolGroups()` returns `ToolGroupRequirement` objects (named requirements like "web_search" added via `withToolGroup()`). Most tests should use `getTools()`.

**Testing with Spring Dependencies:**

- Mock Spring-injected services like `HoroscopeService` using standard mocking frameworks - Pass mocked dependencies to agent constructor for isolated unit testing

===== Testing Multiple LLM Interactions

[tabs]
====
Java::
+
[source,java]
----
@Test
void shouldHandleMultipleLlmInteractions() {
    // Arrange
    var input = new UserInput("Write about space exploration");
    var story = new Story("The astronaut gazed at Earth...");
    ReviewedStory review = new ReviewedStory("Compelling narrative with vivid imagery.");

    // Set up expected responses in order
    context.expectResponse(story);
    context.expectResponse(review);

    // Act
    var writtenStory = agent.writeStory(input, context);
    ReviewedStory reviewedStory = agent.reviewStory(writtenStory, context);

    // Assert
    assertEquals(story, writtenStory);
    assertEquals(review, reviewedStory);

    // Verify both LLM calls were made
    List<LlmInvocation> invocations = context.getLlmInvocations();
    assertEquals(2, invocations.size());

    // Verify first call (writer)
    var writerCall = invocations.get(0);
    assertEquals(0.8, writerCall.getInteraction().getLlm().getTemperature(), 0.01);

    // Verify second call (reviewer)
    var reviewerCall = invocations.get(1);
    assertEquals(0.2, reviewerCall.getInteraction().getLlm().getTemperature(), 0.01);
}
----

Kotlin::
+
[source,kotlin]
----
@Test
fun `should handle multiple LLM interactions`() {
    // Arrange
    val input = UserInput("Write about space exploration")
    val story = Story("The astronaut gazed at Earth...")
    val review = ReviewedStory("Compelling narrative with vivid imagery.")

    // Set up expected responses in order
    context.expectResponse(story)
    context.expectResponse(review)

    // Act
    val writtenStory = agent.writeStory(input, context)
    val reviewedStory = agent.reviewStory(writtenStory, context)

    // Assert
    assertEquals(story, writtenStory)
    assertEquals(review, reviewedStory)

    // Verify both LLM calls were made
    val invocations = context.llmInvocations
    assertEquals(2, invocations.size)

    // Verify first call (writer)
    val writerCall = invocations[0]
    assertEquals(0.8, writerCall.interaction.llm.temperature, 0.01)

    // Verify second call (reviewer)
    val reviewerCall = invocations[1]
    assertEquals(0.2, reviewerCall.interaction.llm.temperature, 0.01)
}
----
====

You can also use Mockito or mockk directly.
Consider this component, using direct injection of `Ai`:

[tabs]
====
Java::
+
[source,java]
----
@Component
public record InjectedComponent(Ai ai) {

    public record Joke(String leadup, String punchline) {
    }

    public String tellJokeAbout(String topic) {
        return ai
                .withDefaultLlm()
                .generateText("Tell me a joke about " + topic);
    }
}
----

Kotlin::
+
[source,kotlin]
----
@Component
class InjectedComponent(private val ai: Ai) {

    data class Joke(val leadup: String, val punchline: String)

    fun tellJokeAbout(topic: String): String {
        return ai
            .withDefaultLlm()
            .generateText("Tell me a joke about $topic")
    }
}
----
====

A unit test using Mockito (Java) or mockk (Kotlin) to verify prompt and hyperparameters:

[tabs]
====
Java::
+
[source,java]
----
class InjectedComponentTest {

    @Test
    void testTellJokeAbout() {
        var mockAi = Mockito.mock(Ai.class);
        var mockPromptRunner = Mockito.mock(PromptRunner.class);

        var prompt = "Tell me a joke about frogs";
        // Yep, an LLM came up with this joke.
        var terribleJoke = """
                Why don't frogs ever pay for drinks?
                Because they always have a tadpole in their wallet!
                """;
        when(mockAi.withDefaultLlm()).thenReturn(mockPromptRunner);
        when(mockPromptRunner.generateText(prompt)).thenReturn(terribleJoke);

        var injectedComponent = new InjectedComponent(mockAi);
        var joke = injectedComponent.tellJokeAbout("frogs");

        assertEquals(terribleJoke, joke);
        Mockito.verify(mockAi).withDefaultLlm();
        Mockito.verify(mockPromptRunner).generateText(prompt);
    }

}
----

Kotlin::
+
[source,kotlin]
----
class InjectedComponentTest {

    @Test
    fun `test tell joke about`() {
        val mockAi = mockk<Ai>()
        val mockPromptRunner = mockk<PromptRunner>()

        val prompt = "Tell me a joke about frogs"
        // Yep, an LLM came up with this joke.
        val terribleJoke = """
            Why don't frogs ever pay for drinks?
            Because they always have a tadpole in their wallet!
        """.trimIndent()

        every { mockAi.withDefaultLlm() } returns mockPromptRunner
        every { mockPromptRunner.generateText(prompt) } returns terribleJoke

        val injectedComponent = InjectedComponent(mockAi)
        val joke = injectedComponent.tellJokeAbout("frogs")

        assertEquals(terribleJoke, joke)
        verify { mockAi.withDefaultLlm() }
        verify { mockPromptRunner.generateText(prompt) }
    }
}
----
====

==== Integration Testing

Integration testing exercises complete agent workflows with real or mock external services while still avoiding actual LLM calls for predictability and speed.

This can ensure:

- Agents are picked up by the agent platform
- Data flow is correct within agents
- Failure scenarios are handled gracefully
- Agents interact correctly with each other and external systems
- The overall workflow behaves as expected
- LLM prompts and hyperparameters are correctly configured

Embabel integration testing is built on top of https://docs.spring.io/spring-framework/reference/testing/integration.html[Spring's excellent integration testing support], thus allowing you to work with real databases if you wish.
Spring's https://docs.spring.io/spring-boot/reference/testing/testcontainers.html[integration with Testcontainers] is particularly userul.

===== Using EmbabelMockitoIntegrationTest

Embabel provides `EmbabelMockitoIntegrationTest` as a base class that simplifies integration testing with convenient helper methods:

[tabs]
====
Java::
+
[source,java]
----
/**
 * Use framework superclass to test the complete workflow of writing and reviewing a story.
 * This will run under Spring Boot against an AgentPlatform instance
 * that has loaded all our agents.
 */
class StoryWriterIntegrationTest extends EmbabelMockitoIntegrationTest {

    @Test
    void shouldExecuteCompleteWorkflow() {
        var input = new UserInput("Write about artificial intelligence");

        var story = new Story("AI will transform our world...");
        var reviewedStory = new ReviewedStory(story, "Excellent exploration of AI themes.", Personas.REVIEWER);

        whenCreateObject(contains("Craft a short story"), Story.class)
                .thenReturn(story);

        // The second call uses generateText
        whenGenerateText(contains("You will be given a short story to review"))
                .thenReturn(reviewedStory.review());

        var invocation = AgentInvocation.create(agentPlatform, ReviewedStory.class);
        var reviewedStoryResult = invocation.invoke(input);

        assertNotNull(reviewedStoryResult);
        assertTrue(reviewedStoryResult.getContent().contains(story.text()),
                "Expected story content to be present: " + reviewedStoryResult.getContent());
        assertEquals(reviewedStory, reviewedStoryResult,
                "Expected review to match: " + reviewedStoryResult);

        verifyCreateObjectMatching(prompt -> prompt.contains("Craft a short story"), Story.class,
                llm -> llm.getLlm().getTemperature() == 0.9 && llm.getToolGroups().isEmpty());
        verifyGenerateTextMatching(prompt -> prompt.contains("You will be given a short story to review"));
        verifyNoMoreInteractions();
    }
}
----

Kotlin::
+
[source,kotlin]
----
/**
 * Use framework superclass to test the complete workflow of writing and reviewing a story.
 * This will run under Spring Boot against an AgentPlatform instance
 * that has loaded all our agents.
 */
class StoryWriterIntegrationTest : EmbabelMockitoIntegrationTest() {

    @Test
    fun `should execute complete workflow`() {
        val input = UserInput("Write about artificial intelligence")

        val story = Story("AI will transform our world...")
        val reviewedStory = ReviewedStory(story, "Excellent exploration of AI themes.", Personas.REVIEWER)

        whenCreateObject(contains("Craft a short story"), Story::class.java)
            .thenReturn(story)

        // The second call uses generateText
        whenGenerateText(contains("You will be given a short story to review"))
            .thenReturn(reviewedStory.review)

        val invocation = AgentInvocation.create(agentPlatform, ReviewedStory::class.java)
        val reviewedStoryResult = invocation.invoke(input)

        assertNotNull(reviewedStoryResult)
        assertTrue(reviewedStoryResult.content.contains(story.text),
            "Expected story content to be present: ${reviewedStoryResult.content}")
        assertEquals(reviewedStory, reviewedStoryResult,
            "Expected review to match: $reviewedStoryResult")

        verifyCreateObjectMatching({ prompt -> prompt.contains("Craft a short story") }, Story::class.java) { llm ->
            llm.llm.temperature == 0.9 && llm.toolGroups.isEmpty()
        }
        verifyGenerateTextMatching { prompt -> prompt.contains("You will be given a short story to review") }
        verifyNoMoreInteractions()
    }
}
----
====

===== Key Integration Testing Features

**Base Class Benefits:**

- `EmbabelMockitoIntegrationTest` handles Spring Boot setup and LLM mocking automatically
- Provides `agentPlatform` and `llmOperations` pre-configured
- Includes helper methods for common testing patterns

**Convenient Stubbing Methods:**

- `whenCreateObject(prompt, outputClass)`: Mock object creation calls
- `whenGenerateText(prompt)`: Mock text generation calls
- Support for both exact prompts and `contains()` matching
- Supports streaming calls by calling `supportsStreaming(true)` in test setup.

**Advanced Verification:**

- `verifyCreateObjectMatching()`: Verify prompts with custom matchers
- `verifyGenerateTextMatching()`: Verify text generation calls
- `verifyNoMoreInteractions()`: Ensure no unexpected LLM calls

**LLM Configuration Testing:**

- Verify temperature settings: `llm.getLlm().getTemperature() == 0.9`
- Check tool groups: `llm.getToolGroups().isEmpty()`
- Validate persona and other LLM options

===== Integration Tests with LLM

Embabel provides integration tests that exercise complete workflows with real LLM providers to verify end-to-end functionality.
These tests are located in the autoconfiguration modules for each LLM provider and verify that the integration with the provider's API works correctly, including features like guardrails, thinking responses, and structured output.

**Examples of LLM Integration Tests:**

[cols="1,3,2", options="header"]
|===
|Test Name
|Description
|LLM Provider

|LLMOpenAiGuardRailsIntegrationIT
|Tests OpenAI integration with guardrails, including moderation API integration using both OpenAI SDK and Spring AI, and validates guardrail invocation for structured output
|OpenAI (gpt-4.1-mini)

|LLMAnthropicGuardRailsIntegrationIT
|Tests Anthropic integration with guardrails, validating that AssistantMessageGuardRail is correctly invoked for structured object responses
|Anthropic (claude-sonnet-4-5)

|LLMGeminiGuardRailsIntegrationIT
|Tests Gemini integration with guardrails, verifying guardrail functionality with structured output for Google's Gemini models
|Gemini (gemini-2.5-flash)
|===

====== Test Structure

These integration tests follow a consistent structure designed to test real LLM interactions while maintaining control over the test environment.

*1. Test Location:*

Integration tests are located within autoconfiguration modules rather than in separate test modules.
This placement ensures tests have direct access to the autoconfiguration classes they're testing and can verify that Spring Boot correctly initializes all required beans.

[source]
----
embabel-agent-autoconfigure/
  models/
    embabel-agent-openai-autoconfigure/
      src/test/java/com/embabel/agent/config/models/openai/
        LLMOpenAiGuardRailsIntegrationIT.java
    embabel-agent-anthropic-autoconfigure/
      src/test/java/com/embabel/agent/config/models/anthropic/
        LLMAnthropicGuardRailsIntegrationIT.java
    embabel-agent-gemini-autoconfigure/
      src/test/java/com/embabel/agent/config/models/gemini/
        LLMGeminiGuardRailsIntegrationIT.java
----

*2. Environment Properties:*

Each test configures the Spring environment through the `@SpringBootTest` annotation's `properties` attribute.
These properties configure the LLM models, timeouts, retries, and logging levels for the test environment.

[source,java]
----
@SpringBootTest(
    properties = {
        "embabel.models.cheapest=gpt-4.1-mini",
        "embabel.models.best=gpt-4.1-mini",
        "embabel.models.default-llm=gpt-4.1-mini",  // <1>
        "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
        "embabel.agent.platform.llm-operations.data-binding.fixedBackoffMillis=6000",


        // Thinking Infrastructure logging
        "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE",
        "logging.level.com.embabel.common.core.thinking=DEBUG"

    }
)
----
<1> The `default-llm` property configures which LLM model to use by default when `ai.withDefaultLlm()` is called

*3. Active Profiles:*

The `@ActiveProfiles` annotation activates Spring profiles that enable specific functionality for the test environment.
The "thinking" profile enables extended thinking capabilities and additional logging for LLM operations.

[source,java]
----
@ActiveProfiles("thinking")  // <1>
----
<1> Activates the "thinking" profile which enables enhanced tracing and thinking block extraction

*4. Configuration Properties Scanning:*

The `@ConfigurationPropertiesScan` annotation enables Spring Boot to discover and bind configuration properties classes.
This is essential for integration tests to load all configuration properties from the `embabel.agent` and `embabel.example` packages.

[source,java]
----
@ConfigurationPropertiesScan(
    basePackages = {
        "com.embabel.agent",
        "com.embabel.example"
    }
)
----

*5. Component Scanning:*

The `@ComponentScan` annotation configures component scanning to discover Spring beans.
Integration tests typically exclude certain components (like exception handlers) that might interfere with test execution.

[source,java]
----
@ComponentScan(
    basePackages = {
        "com.embabel.agent",
        "com.embabel.example"
    }
)
----

*6. Importing Autoconfiguration:*

The `@Import` annotation explicitly imports the autoconfiguration class being tested.
This ensures the specific LLM provider's autoconfiguration is loaded and all required beans are created.

[source,java]
----
@Import({AgentOpenAiAutoConfiguration.class, GuardRailConfiguration.class})  // <1>
----
<1> Imports both the OpenAI autoconfiguration and test-specific guardrail configuration

*7. Autowired Dependencies:*

Integration tests autowire the beans needed for testing.
While tests may autowire multiple components for verification purposes, *only the `Ai` interface is required* to execute LLM operations.

[source,java]
----
@Autowired
private Ai ai;  // <1>

@Autowired
private Autonomy autonomy;  // <2>

@Autowired
private List<LlmService<?>> llms;  // <2>
----
<1> The `Ai` interface is the primary entry point and the only required dependency for making LLM calls
<2> Additional autowired components used for verification and testing purposes

====== Example Test: OpenAI GuardRails Integration

The following example demonstrates a complete integration test from `LLMOpenAiGuardRailsIntegrationIT.java`:

[source,java,linenums]
----

@Test
void testGuardRailInvokedForStructuredCreateObject() {
    logger.info("Starting guardrail structured createObject test");  // <1>

    List<String> guardRailCalled = Collections.synchronizedList(new ArrayList<>());  // <2>

    AssistantMessageGuardRail trackingGuard = new AssistantMessageGuardRail() {  // <3>
        @Override
        public @NotNull String getName() {
            return "StructuredOutputTrackingGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "Tracks guardrail invocation for structured output";
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
            guardRailCalled.add(input);
            logger.info("AssistantMessageGuardRail invoked for structured output: {}", input);
            return new ValidationResult(true, Collections.emptyList());
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull ThinkingResponse<?> response, @NotNull Blackboard blackboard) {
            return new ValidationResult(true, Collections.emptyList());
        }
    };

    PromptRunner runner = ai.withDefaultLlm()  // <4>
            .withGuardRails(trackingGuard);

    String prompt = """
            What is the hottest month in Florida and provide its temperature.
            The name should be the month name, temperature should be in Fahrenheit.
            """;

    MonthItem result = runner.createObject(prompt, MonthItem.class);  // <5>

    assertNotNull(result, "Result should not be null");  // <6>
    assertNotNull(result.getName(), "Month name should not be null");
    assertFalse(guardRailCalled.isEmpty(),
            "AssistantMessageGuardRail should have been called for structured output");
    logger.info("GuardRail was invoked {} time(s) for structured createObject", guardRailCalled.size());
}
----
<1> Logging statement to track test execution
<2> Thread-safe list to track guardrail invocations
<3> Custom guardrail implementation that tracks when it's called
<4> Configure the prompt runner using the default LLM (configured as gpt-4.1-mini in test properties)
<5> Execute the LLM call to create a structured object
<6> Verify the result and guardrail invocation

NOTE: The test uses `withDefaultLlm()` which references the model configured in the `@SpringBootTest` properties (`embabel.models.default-llm=gpt-4.1-mini`).
This approach makes tests more flexible and allows the model to be changed without modifying test code.

===== Testing Annotated Agents

When testing agents built with `@Agent` and `@Action` annotations, you need to verify that:

- The agent metadata is correctly constructed from annotations
- Actions execute with the correct behavior (retry policies, preconditions, etc.)
- The planner selects and executes actions appropriately
- Domain-specific logic works as expected

**Test Structure for Annotated Agents:**

The key steps to test an annotated agent are:

1. Create an instance of your annotated agent class
2. Use `AgentMetadataReader` to extract agent metadata from annotations
3. Create an `AgentProcess` with a dummy or real `AgentPlatform`
4. Provide input data and run the process
5. Verify the output and any side effects

**Example: Testing Action Retry Policy**

Here's a concise example from `RetryActionAnnotationJavaTest` showing how to test an agent with retry behavior:

[source,java]
----
@Test
void retryMethodFailsOnlyOnceSucceedsSecond() {
    // 1. Create the agent instance
    var instance = new JavaAgentWithTwoRetryActions();  // <1>

    // 2. Extract agent metadata from annotations
    var reader = new AgentMetadataReader();
    var agent = reader.createAgentMetadata(instance);  // <2>

    // 3. Create agent process with test platform
    var ap = IntegrationTestUtils.dummyAgentPlatform();
    var agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),  // <3>
            Map.of("input", new JavaRetryTestInput("test"))  // <4>
    );

    // 4. Run and verify
    assertDoesNotThrow(() ->
        agentProcess.run().resultOfType(JavaRetryTestOutput.class));  // <5>

    assertEquals(2, instance.retryInvocations.get(),
        "Retryable method should have been invoked twice");  // <6>
}
----
<1> Instantiate the annotated agent with any required dependencies
<2> Use `AgentMetadataReader` to parse annotations and create agent metadata
<3> Configure the planner type (UTILITY, GOAP, etc.) for the test
<4> Provide input data as a map - keys match action parameter names
<5> Run the process and extract the result of the expected type
<6> Verify behavior using instance fields (like invocation counters)

**The Annotated Agent Under Test:**

[source,java]
----
@Agent(description = "Java agent with retry", planner = PlannerType.GOAP)
class JavaAgentWithTwoRetryActions {

    final AtomicInteger retryInvocations = new AtomicInteger(0);  // <1>

    @AchievesGoal(description = "Process the input")
    @Action(actionRetryPolicyExpression = "${retry-twice}")  // <2>
    public JavaRetryTestOutput firstAction(JavaRetryTestInput input) {
        retryInvocations.incrementAndGet();
        if (retryInvocations.get() == 1)
            throw new RuntimeException("Failed!");  // <3>

        return new JavaRetryTestOutput("Success!");  // <4>
    }
}
----
<1> Use instance fields to track invocations and verify behavior
<2> Configure action behavior through annotations (retry policy, preconditions, etc.)
<3> First invocation fails to test retry behavior
<4> Second invocation succeeds

**Key Testing Patterns:**

- **Dummy AgentPlatform**: Use `IntegrationTestUtils.dummyAgentPlatform()` for lightweight testing without Spring context
- **Instance State**: Access instance fields directly to verify internal behavior (invocation counts, state changes)
- **Input Map**: Provide action parameters as a `Map<String, Object>` where keys match parameter names
- **Result Extraction**: Use `agentProcess.run().resultOfType(ExpectedType.class)` to get strongly-typed results
- **Exception Testing**: Use `assertThrows()` to verify failure scenarios and retry exhaustion