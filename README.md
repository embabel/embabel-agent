# Embabel Agent Framework

![Build](https://github.com/embabel/embabel-agent/actions/workflows/maven.yml/badge.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=embabel_embabel-agent&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=embabel_embabel-agent)
[![Discord](https://img.shields.io/discord/1277751399261798401?logo=discord)](https://discord.gg/t6bjkyj93q)

[//]: # ([![Quality Gate Status]&#40;https://sonarcloud.io/api/project_badges/measure?project=embabel_embabel-agent&metric=alert_status&token=d275d89d09961c114b8317a4796f84faf509691c&#41;]&#40;https://sonarcloud.io/summary/new_code?id=embabel_embabel-agent&#41;)

[//]: # ([![Bugs]&#40;https://sonarcloud.io/api/project_badges/measure?project=embabel_embabel-agent&metric=bugs&#41;]&#40;https://sonarcloud.io/summary/new_code?id=embabel_embabel-agent&#41;)
![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F.svg?style=for-the-badge&logo=Spring-Boot&logoColor=white)
![Apache Tomcat](https://img.shields.io/badge/apache%20tomcat-%23F8DC75.svg?style=for-the-badge&logo=apache-tomcat&logoColor=black)
![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)
![JUnit](https://img.shields.io/badge/JUnit5-25A162.svg?style=for-the-badge&logo=JUnit5&logoColor=white)
![ChatGPT](https://img.shields.io/badge/chatGPT-74aa9c?style=for-the-badge&logo=openai&logoColor=white)
![Jinja](https://img.shields.io/badge/jinja-white.svg?style=for-the-badge&logo=jinja&logoColor=black)
![JSON](https://img.shields.io/badge/JSON-000?logo=json&logoColor=fff)
![GitHub Actions](https://img.shields.io/badge/github%20actions-%232671E5.svg?style=for-the-badge&logo=githubactions&logoColor=white)
![SonarQube](https://img.shields.io/badge/SonarQube-black?style=for-the-badge&logo=sonarqube&logoColor=4E9BCD)
![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white)
![IntelliJ IDEA](https://img.shields.io/badge/IntelliJIDEA-000000.svg?style=for-the-badge&logo=intellij-idea&logoColor=white)
[![License](https://img.shields.io/github/license/embabel/embabel-agent?style=for-the-badge&logo=apache&color=brightgreen)](https://www.apache.org/licenses/LICENSE-2.0)
[![Commits](https://img.shields.io/github/commit-activity/m/embabel/embabel-agent.svg?label=commits&style=for-the-badge&logo=git&logoColor=white)](https://github.com/embabel/embabel-agent/pulse)




<img align="left" src="https://github.com/embabel/embabel-agent/blob/main/embabel-agent-api/images/315px-Meister_der_Weltenchronik_001.jpg?raw=true" width="180">

&nbsp;&nbsp;&nbsp;&nbsp;

Embabel (Em-BAY-bel) is a framework for authoring agentic flows on the JVM that seamlessly mix LLM-prompted interactions
with code and domain models. Supports
intelligent path finding towards goals. Written in Kotlin
but offers a natural usage
model from Java.
From the creator of Spring.

## Key Concepts

Models agentic flows in terms of:

- **Actions**: Steps an agent takes
- **Goals**: What an agent is trying to achieve
- **Conditions**: Conditions to assess before executing an action or determining that a goal has been achieved.
  Conditions are reassessed after each action is executed.
- **Domain model**: Objects underpinning the flow and informing Actions, Goals and Conditions.
- **Plan**: A sequence of actions to achieve a goal. Plans are dynamically formulated by the system, not the programmer.
  The
  system replans after the completion of each action, allowing it to adapt to new information as well as observe the
  effects of the previous action.
  This is effectively an [OODA loop](https://en.wikipedia.org/wiki/OODA_loop).

> Application developers don't usually have to deal with these concepts directly,
> as most conditions result from data flow defined in code, allowing the system to infer
> pre and post conditions.

These concepts underpin these differentiators versus other agent frameworks:

- **Sophisticated planning.** Goes beyond a finite state machine or sequential execution
  with nesting by introducing a true planning step, using a
  non-LLM AI algorithm. This enables the system to perform tasks it wasn’t programmed to do by combining known
  steps in
  a novel order, as well as make decisions about parallelization and other runtime behavior.
- **Superior extensibility and reuse**: Because of dynamic planning, adding more domain objects, actions, goals and
  conditions
  can extend the capability of the system, _without editing FSM definitions_ or existing code.
- **Strong typing and the benefits of object orientation**: Actions, goals and conditions are informed by a domain
  model, which can
  include behavior. Everything is strongly typed and prompts and
  manually authored code interact cleanly. No more magic maps. Enjoy full refactoring support.

Other benefits:

- **Platform abstraction**: Clean separation between programming model and platform internals allows running locally
  while
  potentially offering higher QoS in production without changing application code.
- **Designed for LLM mixing**: It is easy to build applications that mix LLMs, ensuring the most cost-effective yet
  capable solution.
  This enables the system to leverage the strengths of different models for different tasks. In particular, it
  facilitates
  the use of local models for point tasks. This can be important for cost and privacy.
- **Built on Spring and the JVM,** making it easy to access existing enterprise functionality and capabilities.
  For example:
    - Spring can inject and manage agents, including using Spring AOP to decorate functions.
    - Robust persistence and transaction management solutions are available.
- **Designed for testability** from the ground up. Both unit testing and agent end to end testing are easy.

Flows can be authored in one of two ways:

- An annotation-based model similar to Spring MVC, with types annotated with the Spring stereotype `@Agent`, using
  `@Goal`, `@Condition` and
  `@Action` methods.
- Idiomatic Kotlin DSL with `agent {` and `action {` blocks.

Either way, flows are backed by a domain model of objects that can have rich behavior.

> We are working toward allowing natural language actions and goals to be deployed.

The planning step is pluggable.

The default planning approach is
[Goal Oriented Action Planning](https://medium.com/@vedantchaudhari/goal-oriented-action-planning-34035ed40d0b).
GOAP is a popular AI planning algorithm used in gaming. It allows for dynamic decision-making and action selection based
on the current state of the world and the goals of the agent.

Goals, actions and plans are independent of GOAP. Future planning options include:

- Plans created by a reasoning model such as OpenAI o1 or DeepSeek R1.

The framework executes via an `AgentPlatform` implementation.

An agent platform supports the following modes of execution:

- **Focused**, where user code requests particular functionality: User code calls a method to run a particular agent,
  passing in input. This is ideal for code-driven flows such as a flow invoked in response to an incoming event.
- **Closed**, where user intent (or another incoming event) is classified to choose an agent. The platform tries to
  find a
  suitable agent among all the agents it knows about.
  Agent choice is dynamic, but only actions defined within the particular agent
  will run.
- **Open**, where the user's intent is assessed and the platform uses _all_ its resources to try to achieve it. The
  platform tries to find a
  suitable goal among all the goals it knows about and builds a custom agent to achieve it from the start state,
  including relevant actions and conditions. The platform will not proceed if it is unconvinced as to the applicability
  of any goal. The `GoalChoiceApprover` interface provides developers a way to limit goal choice further.

Open mode is the most powerful, but least deterministic.
> In open mode, the platform is capable of finding novel paths that were not envisioned by developers, and even
> combining functionality from multiple providers.

Even in open mode, the platform will only perform individual steps
that have been specified. (Of course, steps may themselves be LLM
transforms, in which case the prompts are controlled by user code but the
results are still non-deterministic.)

Possible future modes:

- **Evolving** mode: Where the platform can work with multiple goals in the same process and modify a running process to
  add further goals and agents.
  For example, an action can realize that it has become important to achieve additional goals.

Embabel agent systems will also support federation, both with other Embabel systems (allowing planning to incorporate
remote actions and goals) and third party agent frameworks.

## Quick Start

Get an agent running in under 5 minutes.

Create your own agent repo from our [Java](https://github.com/embabel/java-agent-template)
or [Kotlin](https://github.com/embabel/kotlin-agent-template) GitHub template by clicking the "Use this template"
button.

You can also create your own Embabel agent project locally with our quick start tool:

```
uvx --from git+https://github.com/embabel/project-creator.git project-creator
```

Choose Java or Kotlin and specify your project name and package name and you'll have an agent running in under a minute,
if you already have an `OPENAI_API_KEY` and have Maven installed.

**📚 For examples and tutorials**, see
the [Embabel Agent Examples Repository](https://github.com/embabel/embabel-agent-examples)

**🚗 For a realistic example application**, see the [Tripper travel planner agent](https://github.com/embabel/tripper)

<img src="images/tripper_output1.jpg" alt="Travel Planner Output" width="600"/>

*AI-generated travel itinerary with detailed recommendations*

<img src="images/tripper_map.jpg" alt="Interactive map" width="600"/>

*Map link included in output*

## Why Is Embabel Needed?

TL;DR Because the evolution of agent frameworks is early and there's a lot of room for improvement; because an agent
framework on the JVM will deliver great business value.

- _Why do we need an agent framework at all_? We can write code without higher level abstractions, directly invoking
  LLMs and controlling flow directly in code. However, a higher level agent framework offers compelling benefits. For
  example:
    - Breaking up LLM interactions, making them simpler and more focused. This maximizes reuse and minimizes cost and
      errors. It often allows us to use cheaper models for point interactions.
    - Facilitating both unit and integration testing, which remain as important with agentic systems as with any other
      software systems.
    - Increasing composability where subflows and individual actions can be reused
    - Making applications more manageable and robust, enabling a workflow manager to control their execution and retry
      operations while maintaining previous state
    - Enhancing safety through the ability to apply guardrails in many places
- _Why do we need an agent framework for the JVM when solutions exist in Python?_: While this space is presently
  better developed in Python (or even TypeScript), it's early and there's plenty of room for novel and potentially
  superior
  approaches. The key adjacency is often not the LLM--which is a simple HTTP call away--but existing code and
  infrastructure
  assets that are more likely on the JVM than in Python.
- _Why not use just Spring AI?_ Spring AI is great. We build on it, and embrace the Spring component model. However, we
  believe that most applications should work with higher
  level APIs. An analogy: Spring AI exists at the level of the Servlet API, while Embabel is more like Spring MVC.
  Complex requirements are much easier to express and test in Embabel than with direct use of Spring AI.
- _Why not attempt to contribute this project to Spring?_ This project requires different governance
  from Spring, where most projects exist in stable environments and dependability and stability outweighs rapid
  innovation. Second, the
  concepts are not JVM-specific. We hope that Embabel will become the leading agent framework across platforms. While
  the Spring brand is valuable in Java, it is not in TypeScript or Python.

## Show Me The Code

In Java or Kotlin, agent implementation code is intuitive and easy to test.

<details open>
<summary>Java</summary>

```java

@Agent(description = "Find news based on a person's star sign")
public class StarNewsFinder {

    private final HoroscopeService horoscopeService;
    private final int storyCount;

    // Services are injected by Spring
    public StarNewsFinder(
            HoroscopeService horoscopeService,
            @Value("${star-news-finder.story.count:5}") int storyCount) {
        this.horoscopeService = horoscopeService;
        this.storyCount = storyCount;
    }

    @Action
    public StarPerson extractStarPerson(UserInput userInput, OperationContext context) {
        return context.ai()
                .withLlm(OpenAiModels.GPT_41)
                .createObjectIfPossible(
                        """
                                Create a person from this user input, extracting their name and star sign:
                                %s""".formatted(userInput.getContent()),
                        StarPerson.class
                );
    }

    @Action
    public Horoscope retrieveHoroscope(StarPerson starPerson) {
        return new Horoscope(horoscopeService.dailyHoroscope(starPerson.sign()));
    }

    // toolGroups specifies tools that are required for this action to run
    @Action(toolGroups = {CoreToolGroups.WEB})
    public RelevantNewsStories findNewsStories(
            StarPerson person,
            Horoscope horoscope,
            OperationContext context) {
        var prompt = """
                %s is an astrology believer with the sign %s.
                Their horoscope for today is:
                    <horoscope>%s</horoscope>
                Given this, use web tools and generate search queries
                to find %d relevant news stories summarize them in a few sentences.
                Include the URL for each story.
                Do not look for another horoscope reading or return results directly about astrology;
                find stories relevant to the reading above.
                
                For example:
                - If the horoscope says that they may
                want to work on relationships, you could find news stories about
                novel gifts
                - If the horoscope says that they may want to work on their career,
                find news stories about training courses.""".formatted(
                person.name(), person.sign(), horoscope.summary(), storyCount);
        return context.ai()
                .withDefaultLlm()
                .createObject(prompt, RelevantNewsStories.class);
    }

    // The @AchievesGoal annotation indicates that completing this action
    // achieves the given goal, so the agent can be complete
    @AchievesGoal(
            description = "Write an amusing writeup for the target person based on their horoscope and current news stories",
            export = @Export(
                    remote = true,
                    name = "starNewsWriteupJava",
                    startingInputTypes = {StarPerson.class, UserInput.class})
    )
    @Action
    public Writeup writeup(
            StarPerson person,
            RelevantNewsStories relevantNewsStories,
            Horoscope horoscope,
            OperationContext context) {
        var llm = LlmOptions
                .withModel(OpenAiModels.GPT_41_MINI)
                // High temperature for creativity
                .withTemperature(0.9);

        var newsItems = relevantNewsStories.getItems().stream()
                .map(item -> "- " + item.getUrl() + ": " + item.getSummary())
                .collect(Collectors.joining("\n"));

        var prompt = """
                Take the following news stories and write up something
                amusing for the target person.
                
                Begin by summarizing their horoscope in a concise, amusing way, then
                talk about the news. End with a surprising signoff.
                
                %s is an astrology believer with the sign %s.
                Their horoscope for today is:
                    <horoscope>%s</horoscope>
                Relevant news stories are:
                %s
                
                Format it as Markdown with links.""".formatted(
                person.name(), person.sign(), horoscope.summary(), newsItems);
        return context.ai()
                .withLlm(llm)
                .createObject(prompt, Writeup.class);
    }
}
```

</details>

<details>
<summary>Kotlin</summary>

```kotlin
@Agent(description = "Find news based on a person's star sign")
class StarNewsFinder(
    // Services such as Horoscope are injected by Spring
    private val horoscopeService: HoroscopeService,
    // Potentially externalized by Spring
    @param:Value("\${star-news-finder.story.count:5}")
    private val storyCount: Int = 5,
) {

    @Action
    fun extractPerson(
        userInput: UserInput,
        context: OperationContext
    ): StarPerson =
        // All prompts are typesafe
        context.ai().withDefaultLlm()
            .createObject("Create a person from this user input, extracting their name and star sign: $userInput")

    // This action doesn't use an LLM
    // Embabel makes it easy to mix LLM use with regular code
    @Action
    fun retrieveHoroscope(starPerson: StarPerson) =
        Horoscope(horoscopeService.dailyHoroscope(starPerson.sign))

    // This action uses tools
    // "toolGroups" specifies tools that are required for this action to run
    @Action(toolGroups = [ToolGroup.WEB])
    fun findNewsStories(
        person: StarPerson,
        horoscope: Horoscope,
        context: OperationContext
    ): RelevantNewsStories =
        context.ai().withDefaultLlm().createObject(
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

    // The @AchievesGoal annotation indicates that completing this action
    // achieves the given goal, so the agent run will be complete
    @AchievesGoal(
        description = "Write an amusing writeup for the target person based on their horoscope and current news stories",
    )
    @Action
    fun writeup(
        person: StarPerson,
        relevantNewsStories: RelevantNewsStories,
        horoscope: Horoscope,
        context: OperationContext,
    ): Writeup =
        context.ai()
            .withLlm(
                LlmOptions
                    .withModel(model)
                    .withTemperature(0.9)
            )
            .createObject(
                """
            Take the following news stories and write up something
            amusing for the target person.

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
```

</details>


The following domain classes ensure type safety:

<details open>
<summary>Java</summary>

```java

@JsonClassDescription("Person with astrology details")
@JsonDeserialize(as = StarPerson.class)
public record StarPerson(
        String name,
        @JsonPropertyDescription("Star sign") String sign
) implements Person {

    @JsonCreator
    public StarPerson(
            @JsonProperty("name") String name,
            @JsonProperty("sign") String sign
    ) {
        this.name = name;
        this.sign = sign;
    }

    @Override
    public String getName() {
        return name;
    }
}

public record Horoscope(String summary) {
}

@JsonClassDescription("Writeup relating to a person's horoscope and relevant news")
public record Writeup(String text) implements HasContent {

    @JsonCreator
    public Writeup(@JsonProperty("text") String text) {
        this.text = text;
    }

    @Override
    public String getContent() {
        return text;
    }
}

```

</details>

<details>
<summary>Kotlin</summary>

```kotlin
data class RelevantNewsStories(
    val items: List<NewsStory>
)

data class NewsStory(
    val url: String,

    val summary: String,
)

data class Subject(
    val name: String,
    val sign: String,
)

data class Horoscope(
    val summary: String,
)

data class FunnyWriteup(
    override val text: String,
) : HasContent
```

</details>

It's easy to unit test your agents to ensure that they correctly execute logic
and pass the correct prompts and hyperparameters to LLMs. For example:

```java
public class StarNewsFinderTest {

    @Test
    void writeupPromptMustContainKeyData() {
        HoroscopeService horoscopeService = mock(HoroscopeService.class);
        StarNewsFinder starNewsFinder = new StarNewsFinder(horoscopeService, 5);
        var context = new FakeOperationContext();
        context.expectResponse(new com.embabel.example.horoscope.Writeup("Gonna be a good day"));

        NewsStory cockatoos = new NewsStory(
                "https://fake.com.au",
                "Cockatoo behavior",
                "Cockatoos are eating cabbages"
        );

        NewsStory emus = new NewsStory(
                "https://morefake.com.au",
                "Emu movements",
                "Emus are massing"
        );

        StarPerson starPerson = new StarPerson("Lynda", "Scorpio");
        RelevantNewsStories relevantNewsStories = new RelevantNewsStories(Arrays.asList(cockatoos, emus));
        Horoscope horoscope = new Horoscope("This is a good day for you");

        starNewsFinder.writeup(starPerson, relevantNewsStories, horoscope, context);

        var prompt = context.getLlmInvocations().getFirst().getPrompt();
        var toolGroups = context.getLlmInvocations().getFirst().getInteraction().getToolGroups();


        assertTrue(prompt.contains(starPerson.getName()));
        assertTrue(prompt.contains(starPerson.sign()));
        assertTrue(prompt.contains(cockatoos.getSummary()));
        assertTrue(prompt.contains(emus.getSummary()));

        assertTrue(toolGroups.isEmpty(), "The LLM should not have been given any tool groups");
    }
}
```

## Dog Food Policy

We believe that all aspects of software development can and should
be greatly accelerated through the use of AI agents. The ultimate decision
makers remain human, but they can and should be greatly augmented.

> This project practices extreme dogfooding.

<!-- TODO photo of Duke with kibble -->

Our key principles:

1. **We will use AI agents to help every aspect of the project:** coding, documentation, producing marketing copy etc.
   Any
   human performing a task should ask why it cannot be automated, and strive toward maximum automation.
2. **Developers retain ultimate control.** Developers are responsible for guiding agents toward the solution and
   iterating
   as necessary. A developer who commits or merges an agent contribution
   is responsible for ensuring that it meets the project coding standards, which are
   independent of the use of agents. For example, code must be human readable.
3. **We will use only open source agents built on the Embabel platform,** and contribute any improvements. While
   commercial coding agents
   may be more advanced, we believe that our
   platform is the best general solution for automation and by dogfooding we will improve it fastest.
   By open sourcing agents used on our open source projects, we will maximize benefit to the community.
4. **We will prioritize agents that help accelerate our progress.** Per the flight safety advice to fit your own mask
   before helping others, we will prioritize
   agents that help us accelerate our own progress. This will not only produce useful examples, but increase overall
   project velocity.

Developers must carefully read all code they commit and improve generated code if possible.

## Getting Started

- Get the bits
- Set up your environment
- Run the application

### Getting the bits

Choose one of the following:

- Clone the repository via `git clone https://github.com/embabel/embabel-agent`
- Create a new Spring Boot project and add the necessary dependencies (see "Using Embabel Agent Framework in Your
  Project" below)

### Environment variables

> Environment variables are consistent with common usage, rather than Spring AI.
> For example, we prefer `OPENAI_API_KEY` to `SPRING_AI_OPENAI_API_KEY`.

Required:

- `OPENAI_API_KEY`: For the OpenAI API

Optional:

- `ANTHROPIC_API_KEY`: For the Anthropic API. Necessary for the coding agent.

> We strongly recommend providing both an OpenAI and Anthropic key, as some examples require both. And it's important to
> try to find the best LLM for a given task, rather than automatically choose a familiar provider.

### Services

You will need a Docker Desktop version [`>4.43.2`](https://docs.docker.com/desktop/release-notes/).
Be sure to activate the following MCP tools from the catalog:

- Brave Search
- Fetch
- Puppeteer
- Wikipedia

> You can also set up your own MCP tools using Spring AI conventions. See the `application-docker-desktop.yml` file for
> an example.

If you're running Ollama locally, set the `ollama` profile and Embabel will automatically connect to your Ollama
endpoint and make all models available.

### Running

Create your own agent project with

```
uvx --from git+https://github.com/embabel/project-creator.git project-creator
```

### Example Agents

> **📚 For examples and tutorials**, see
> the [Embabel Agent Examples Repository](https://github.com/embabel/embabel-agent-examples)

```bash
# Clone and run examples
git clone https://github.com/embabel/embabel-agent-examples
cd embabel-agent-examples/scripts/kotlin
./shell.sh
```

#### Shell Commands

Spring Shell is an easy way to interact with the Embabel agent framework, especially during development.

Type `help` to see available commands. Use `execute` or `x` to run an agent:

```
execute "Lynda is a Scorpio, find news for her" -p -r
```

This will look for an agent, choose the star finder agent and
run the flow. `-p` will log prompts `-r` will log LLM responses.
Omit these for less verbose logging.

Options:

- `-p` logs prompts
- `-r` logs LLM responses

Use the `chat` command to enter an interactive chat with the agent.
It will attempt to run the most appropriate
agent for each command.

> Spring Shell supports history. Type `!!` to repeat the last command.
> This will survive restarts, so is handy when iterating on an agent.

#### Further examples

Example commands within the shell:

```
# Perplexity style deep research
# Requires both OpenAI and Anthropic keys and Docker Desktop with the MCP extension (or your own web tools)
execute "research the recent australian federal election. what is the position of the greens party?"

# x is a shortcut for execute
x "fact check the following: holden cars are still made in australia; the koel is a bird native only to australia; fidel castro is justin trudeau's father"

```

### Bringing in additional LLMs

#### Local models with well-known providers

The Embabel Agent Framework supports local models from:

- Ollama: Simply set the `ollama` profile and your local Ollama endpoint will be queries. All local models will be
  available.
- Docker: Set the `docker` profile and your local Docker endpoint will be queried. All local models will be available.

#### Custom LLMs

You can define an LLM for any provider for which a Spring AI `ChatModel` is available.

Simply define Spring beans of type `Llm`.
See the `OpenAiConfiguration` class as an example.

Remember:

- Provide the knowledge cutoff date if you know it
- Make the configuration class conditional on any required API key.

## Roadmap

This project is in its early stages, but we have big plans.
The milestones and issues in this repository are a good reference.
Our key goals:

- **Become the natural way to Gen AI-enable Java applications**, and especially those built on Spring.
- **Prove the power of the model**. Demonstrate that this model is the most capable agent
  model. See the [Dunnart milestone](https://github.com/embabel/embabel-agent/milestone/4) for details.
  In particular:
    - Demonstrate the power of extensibility without modification, by adding goals and actions
    - Demonstrate the potential to become the PaaS for natural language
    - Demonstrate the potential of agent federation within the GOAP model
    - Demonstrate budget-aware agents, such as "Research the following topic, spending up to 20c if you are still
      learning"
    - Integrate with data stores and demonstrate the power of surfacing existing functionality inside an organization
- **Take the model to other platforms**: The conceptual framework is not JVM specific. Once established, we intend to
  create TypeScript
  and Python projects.

There is a lot to do, and you are awesome. We look forward to your contribution!

## Application Design

### Domain objects

Applications center around domain objects. These can be instantiated by LLMs or user
code, and manipulated by user code.

Use Jackson annotations to help LLMs with descriptions as well as mark fields to ignore.
For example:

```kotlin
@JsonClassDescription("Person with astrology details")
data class StarPerson(
    override val name: String,
    @get:JsonPropertyDescription("Star sign")
    val sign: String,
) : Person
```

See [Java Json Schema Generation - Module Jackson](https://github.com/victools/jsonschema-generator/tree/main/jsonschema-module-jackson)
for documentation of the library used.

Domain objects can have behaviors that are automatically exposed to LLMs when they are in scope. Simply annotate methods
with the Spring AI `@Tool` annotation.

> When exposing `@Tool` methods on domain objects, be sure that the tool is safe to invoke. Even the best LLMs can get
> trigger-happy. For example, be careful about methods that can mutate or delete data. This is likely better modeled via
> an explicit call to a non-tool method on the same domain class, in a code action.

## Using Embabel as an MCP server

You can use the Embabel agent platform as an MCP server from a
UI like Claude Desktop. The Embabel MCP server is available over SSE.

Configure Claude Desktop as follows in your `claude_desktop_config.yml`:

```json
{
  "mcpServers": {
    "embabel": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://localhost:8080/sse"
      ]
    }
  }
}

```

See [MCP Quickstart for Claude Desktop Users](https://modelcontextprotocol.io/quickstart/user) for how to configure
Claude Desktop.

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is a helpful tool for interacting with your
Embabel
SSE server, manually invoking tools and checking the exposed prompts and resources.

Start the MCP Inspector with:

```bash
npx @modelcontextprotocol/inspector
```

## Consuming MCP Servers

The Embabel Agent Framework provides built-in support for consuming Model Context Protocol (MCP) servers, allowing you
to extend your applications with powerful AI capabilities through standardized interfaces.

### What is MCP?

Model Context Protocol (MCP) is an open protocol that standardizes how applications provide context and extra
functionality to large language models. Introduced by Anthropic, MCP has emerged as the de facto standard for connecting
AI agents to tools, functioning as a client-server protocol where:

- **Clients** (like Embabel Agent) send requests to servers
- **Servers** process those requests to deliver necessary context to the AI model

MCP simplifies integration between AI applications and external tools, transforming an "M×N problem" into an "M+N
problem" through standardization - similar to what USB did for hardware peripherals.

### Configuring MCP in Embabel Agent

To configure MCP servers in your Embabel Agent application, add the following to your `application.yml`:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: embabel
        version: 1.0.0
        request-timeout: 30s
        type: SYNC
        stdio:
          connections:
            docker-mcp:
              command: docker
              args:
                - run
                - -i
                - --rm
                - alpine/socat
                - STDIO
                - TCP:host.docker.internal:8811
```

This configuration sets up an MCP client that connects to a Docker-based MCP server. The connection uses STDIO transport
through Docker's socat utility to connect to a TCP endpoint.

### Docker Desktop MCP Integration

Docker has embraced MCP with their Docker MCP Catalog and Toolkit, which provides:

1. **Centralized Discovery** - A trusted hub for discovering MCP tools integrated into Docker Hub
2. **Containerized Deployment** - Run MCP servers as containers without complex setup
3. **Secure Credential Management** - Centralized, encrypted credential handling
4. **Built-in Security** - Sandbox isolation and permissions management

The Docker MCP ecosystem includes over 100 verified tools from partners like Stripe, Elastic, Neo4j, and more, all
accessible through Docker's infrastructure.

### Learn More

- [Docker MCP Documentation](https://docs.docker.com/desktop/features/gordon/mcp/)
- [Docker MCP Servers Repository](https://github.com/docker/mcp-servers)
- [Introducing Docker MCP Catalog and Toolkit](https://www.docker.com/blog/introducing-docker-mcp-catalog-and-toolkit/)
- [MCP Introduction and Overview](https://www.philschmid.de/mcp-introduction)

## A2A

Embabel integrates with the [A2A](https://github.com/google-a2a/A2A) protocol, allowing you to connect to other
A2A-enabled agents and
services.

Enable the `a2a` Spring profile to start the A2A server.

You'll need the following environment variable:

- `GOOGLE_STUDIO_API_KEY`: Your Google Studio API key, which is used for Gemini.

Start the Google A2A web interface using the `a2a` Docker profile:

```bash
docker compose --profile a2a up
```

Go to the web interface running within the container at `http://localhost:12000/`.

Connect to your agent at `host.docker.internal:8080/a2a`. Note that `localhost:8080/a2a` won't work as the server
cannot access it when running in a Docker container.

## Running Tests

Run the tests via Maven.

```bash
mvn test
```

This will run both unit and integration tests
but will not require an internet connection or any external services.

## Spring profiles

Spring profiles are used to configure the application for different environments and behaviors.

Interaction profiles:

- `shell`: Runs agent in interactive shell. Does not start web process.

Model profiles:

- `ollama`: Looks for Ollama models. You will need to have Ollama installed locally and the relevant models pulled.
- `docker-desktop`: Looks for Docker-managed local models when running outside Docker but talking to Docker Desktop with
  the MCP extension. **This is recommended for the best experience, with Docker-provided web tools.**
- `docker`: Looks for Docker-managed local models when running in a Docker container.

Logging profiles:

- `severance`: [Severance](https://www.youtube.com/watch?v=xEQP4VVuyrY&ab_channel=AppleTV) specific logging. Praise
  Kier!
- `starwars`: Star Wars specific logging. Feel the force
- `colossus`: Colossus specific logging. The Forbin Project.

## Testing

A key goal of this framework is ease of testing.
Just as Spring eased testing of early enterprise Java applications,
this framework facilitates testing of AI applications.

Types of testing:

- Unit tests: All agents are unit testable, like any Spring-managed beans. Construct them with mock objects; call
  individual action methods. The testing library facilitates testing prompts.
- Integration tests: tbd

## Logging

All logging in this project is either debug logging in the relevant
class itself, or results from the stream of events of type `AgentEvent`.

Edit `application.yml` if you want to see debug logging from the relevant classes and packages.

Available logging experiences:

- `severance`: Severance logging. Praise Kier
- `starwars`: Star Wars logging. Feel the force. The default as it's understood throughout the galaxy.
- `colossus`: Colossus logging. The Forbin Project.
- `montypython`: Monty Python logging. No one expects it.
- `hh`: Hitchhiker's Guide to the Galaxy logging. The answer is 42.

If none of these profiles is chosen, Embabel will use vanilla logging.
This makes me sad.

## Adding Embabel Agent Framework to Your Project

### Maven

The easiest way is to add the Embabel Spring Boot starter dependency to your `pom.xml`:

```xml

<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter</artifactId>
    <version>${embabel-agent.version}</version>
</dependency>
```

### Gradle (Kotlin DSL)

Add the required repositories to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "embabel-releases"
        url = uri("https://repo.embabel.com/artifactory/libs-release")
        mavenContent {
            releasesOnly()
        }
    }
    maven {
        name = "embabel-snapshots"
        url = uri("https://repo.embabel.com/artifactory/libs-snapshot")
        mavenContent {
            snapshotsOnly()
        }
    }
    maven {
        name = "Spring Milestones"
        url = uri("https://repo.spring.io/milestone")
    }
}
```

Add the Embabel Agent starter:

```kotlin
dependencies {
    implementation('com.embabel.agent:embabel-agent-starter:${embabel-agent.version}')
}
```

### Gradle (Groovy DSL)

Add the required repositories to your `build.gradle`:

```groovy
repositories {
    mavenCentral()
    maven {
        name = 'embabel-snapshots'
        url = 'https://repo.embabel.com/artifactory/libs-snapshot'
        mavenContent {
            snapshotsOnly()
        }
    }
    maven {
        name = 'Spring Milestones'
        url = 'https://repo.spring.io/milestone'
    }
}
```

Add the Embabel Agent starter:

```groovy
dependencies {
    implementation 'com.embabel.agent:embabel-agent-starter:0.1.0-SNAPSHOT'
}
```

> **Note:** The Spring Milestones repository is required because the Embabel BOM (`embabel-agent-dependencies`) has
> transitive dependencies on experimental Spring components, specifically the `mcp-bom`. This BOM is not available on
> Maven Central and is only published to the Spring milestone repository. Unlike Maven, Gradle does not inherit
> repository
> configurations declared in parent POMs or BOMs. Therefore, it is necessary to explicitly declare the Spring milestone
> repository in your repositories block to ensure proper resolution of all transitive dependencies.

## Repository

Binary Packages are located in Embabel Maven Repository.
You would need to add Embabel Snapshot Repository to your pom.xml or configure in settings.xml

```xml

<repositories>
    <repository>
        <id>embabel-snapshots</id>
        <url>https://repo.embabel.com/artifactory/libs-snapshot</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

## Contributing

We welcome contributions to the Embabel Agent Framework.

Look at the [coding style guide](embabel-agent-api/.embabel/coding-style.md) for style guidelines.
This file also informs coding agent behavior.

## Miscellaneous

- _Why the name Embabel?_
  The "babel" part is ultimately inspired by the story of the Tower of Babel, perhaps via Douglas
  Adams' [babelfish](https://www.youtube.com/watch?v=iuumnjJWFO4&ab_channel=BBCStudios).
  Per @lasuac:
  _While Adams' fish in the ear enabled universal translation between species, Embabel aims at translating human intent
  to JVM code, AI models, and enterprise systems._
  "embabel" also sounds like "enable."
- Milestone names are Australian animals. Mythical animals such as "bunyip" and "yowie" are used for futures that may or
  not be implemented.
- README badges come from [here](https://github.com/Ileriayo/markdown-badges)
  and [here](https://home.aveek.io/GitHub-Profile-Badges/).

## Star history

[![Star History Chart](https://api.star-history.com/svg?repos=embabel/embabel-agent&type=Date)](https://star-history.com/#embabel/embabel-agent&Date)

## Contributors

[![Embabel contributors](https://contrib.rocks/image?repo=embabel/embabel-agent)](https://github.com/embabel/embabel-agent/graphs/contributors)



--------------------
(c) Embabel Software Inc 2024-2025.
