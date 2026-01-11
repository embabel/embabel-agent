# Dynamic Tool Injection Specification

## Overview

This specification describes **Dynamic Tool Injection** in Embabel—a mechanism for adding tools to an active LLM
conversation based on runtime conditions, without losing conversation context or restarting from scratch.

### Motivation

Static tool sets are limiting. Real-world agent scenarios require tools to become available based on runtime conditions:

1. **Entity Discovery** (this spec): A `Customer` returned from search exposes instance methods (`getAverageSpend()`,
   `getRecentOrders()`)

2. **Progressive Disclosure** (future): Basic tools unlock advanced tools as the agent demonstrates competence

3. **Phase-Based Tools** (future): Different tools available based on conversation phase (planning vs execution)

**Primary Use Case - Entity Discovery**:

```kotlin
// User: "What's John Smith's average spend?"
// 1. LLM calls searchCustomer("John Smith")
// 2. Returns Customer instance
// 3. LLM now sees: customer_c123_getAverageSpend, customer_c123_getRecentOrders
// 4. LLM calls customer_c123_getAverageSpend()
```

### Goals

1. Allow domain entities to declare instance methods as LLM-callable tools (`@ToolProvider`, `@LlmTool`)
2. Automatically expose these tools when the entity is returned from another tool
3. Maintain full conversation history (no context loss)
4. Embabel owns the tool loop—no dependency on Spring AI Advisors
5. Design for extensibility (other injection strategies can be added later)

### Non-Goals

1. Token optimization via tool search/indexing (see Spring AI's Tool Search Tool)
2. Conditional unlock strategies (future work)
3. Phase-based tool switching (future work)
4. Modifying Spring AI internals
5. Streaming support (initial implementation)

---

## Core Concepts

### Tool Injection Strategy

The central abstraction—determines when and what tools to inject:

```kotlin
package com.embabel.agent.spi.toolloop

/**
 * Strategy for dynamically injecting tools during a conversation.
 * 
 * Implementations examine tool call results and conversation state
 * to determine if additional tools should become available.
 */
interface ToolInjectionStrategy {

    /**
     * Called after each tool execution to determine if new tools should be injected.
     * 
     * @param context The current state of the tool loop
     * @return Tools to add, or empty list if none
     */
    fun evaluateToolResult(context: ToolInjectionContext): List<ToolCallback>
}

/**
 * Context provided to injection strategies for decision-making.
 */
data class ToolInjectionContext(
    val conversationHistory: List<Message>,
    val currentTools: List<ToolCallback>,
    val lastToolCall: ToolCallResult,
    val iterationCount: Int,
)

data class ToolCallResult(
    val toolName: String,
    val toolInput: String,
    val result: String,  // JSON
    val resultObject: Any?,  // Deserialized, if possible
)
```

### ToolProviderStrategy

The primary strategy—detects `@ToolProvider` objects in tool results and extracts their `@LlmTool` methods:

```kotlin
/**
 * Injects tools from @ToolProvider instances returned by other tools.
 * 
 * When a tool returns an object (or list of objects) annotated with @ToolProvider,
 * this strategy extracts all @LlmTool methods and makes them available as
 * callable tools bound to that specific instance.
 */
class ToolProviderStrategy(
    private val toolProviderExtractor: ToolProviderExtractor,
) : ToolInjectionStrategy {

    override fun evaluateToolResult(context: ToolInjectionContext): List<ToolCallback> {
        val resultObject = context.lastToolCall.resultObject ?: return emptyList()

        return when {
            resultObject.hasToolProviderAnnotation() ->
                toolProviderExtractor.extractToolCallbacks(resultObject)

            resultObject is Collection<*> ->
                resultObject.filterNotNull()
                    .filter { it.hasToolProviderAnnotation() }
                    .flatMap { toolProviderExtractor.extractToolCallbacks(it) }

            else -> emptyList()
        }
    }
}
```

> **Note**: The `ToolInjectionStrategy` interface is designed for extensibility. Future strategies could include
> conditional unlocks, phase-based tools, or skill acquisition patterns. For now, only `ToolProviderStrategy` is
> implemented.

---

## @ToolProvider Annotation (Entity Strategy)

Marks a class whose instances can provide tools at runtime:

```kotlin
package com.embabel.agent.api.annotation

/**
 * Marks a class as a provider of instance-level LLM tools.
 * 
 * When an object of this class is returned from a tool call during
 * an LLM conversation, its @LlmTool methods become available as
 * callable tools for the remainder of that conversation.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolProvider(
    /**
     * Optional prefix for generated tool names.
     * If empty, derived from class name.
     * 
     * The final tool name format is: {prefix}_{instanceId}_{methodName}
     */
    val prefix: String = "",

    /**
     * Property or method name that provides a unique instance identifier.
     * Used to generate unique tool names when multiple instances exist.
     * Defaults to "id".
     */
    val instanceIdProperty: String = "id",
)
```

### @LlmTool Annotation

Marks a method on a `@ToolProvider` class as an LLM-callable tool:

```kotlin
package com.embabel.agent.api.annotation

/**
 * Marks a method as callable by the LLM when the containing
 * @ToolProvider instance is in scope.
 * 
 * Similar to Spring AI's @Tool but for instance methods on
 * dynamically discovered entities.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmTool(
    /**
     * Description of what this tool does.
     * This is sent to the LLM to help it decide when to use the tool.
     */
    val description: String,

    /**
     * Optional explicit name. If empty, derived from method name.
     */
    val name: String = "",
)
```

### Example Usage

```kotlin
@ToolProvider(prefix = "customer")
class Customer(
    val id: String,
    val name: String,
    val email: String,
    private val orderRepository: OrderRepository,
    private val spendCalculator: SpendCalculator,
) {
    @LlmTool("Get this customer's average monthly spend over the last 12 months")
    fun getAverageSpend(): Money {
        return spendCalculator.calculateAverageMonthlySpend(id)
    }

    @LlmTool("Get this customer's most recent orders")
    fun getRecentOrders(
        @ToolParam("Maximum number of orders to return") limit: Int = 10
    ): List<Order> {
        return orderRepository.findByCustomerId(id, limit)
    }

    @LlmTool("Check if this customer is eligible for a discount")
    fun checkDiscountEligibility(): DiscountEligibility {
        // ...
    }
}

// Order could also be a @ToolProvider, enabling nested discovery
@ToolProvider(prefix = "order")
class Order(
    val id: String,
    val customerId: String,
    val items: List<LineItem>,
) {
    @LlmTool("Get the line items in this order")
    fun getLineItems(): List<LineItem> = items

    @LlmTool("Calculate the total value of this order")
    fun calculateTotal(): Money {
        // ...
    }
}
```

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      PromptRunner                                │
│                                                                  │
│  .withToolProviderDiscovery()      ←── enable entity discovery  │
│  .createObject<T>(prompt)                                        │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ChatClientLlmOperations                         │
│                                                                  │
│  Delegates to EmbabelToolLoop when injection strategy present    │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                    EmbabelToolLoop                               │
│                                                                  │
│  - Owns the tool execution loop (not Spring AI)                  │
│  - Calls ChatModel directly for LLM inference                    │
│  - Executes tool callbacks manually                              │
│  - Invokes ToolInjectionStrategy after each tool call            │
│  - Maintains full conversation history                           │
└─────────────────────────┬───────────────────────────────────────┘
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
┌───────────────────────┐   ┌───────────────────────────────────┐
│ToolInjectionStrategy  │   │ ToolProviderExtractor             │
│                       │   │                                   │
│ - ToolProviderStrategy│   │ - Extracts @LlmTool methods       │
│   (entity discovery)  │   │ - Creates BoundMethodToolCallback │
│                       │   │                                   │
└───────────────────────┘   └───────────────────────────────────┘
```

### Key Classes

#### EmbabelToolLoop

The core class that replaces Spring AI's internal tool loop:

```kotlin
package com.embabel.agent.spi.toolloop

/**
 * Embabel's own tool execution loop.
 * 
 * Unlike Spring AI's internal loop, this gives us full control over:
 * - Message capture and history management
 * - Dynamic tool injection via strategies
 * - Observability and event emission
 * - Integration with Embabel's autonomy system
 */
class EmbabelToolLoop(
    private val chatModel: ChatModel,
    private val objectMapper: ObjectMapper,
    private val injectionStrategy: ToolInjectionStrategy,
    private val eventPublisher: ProcessEventPublisher?,
    private val maxIterations: Int = 20,
) {

    /**
     * Execute a conversation with tool calling until completion.
     * 
     * @param initialMessages The starting messages (system + user)
     * @param initialToolCallbacks The initially available tools
     * @param chatOptions LLM options (model, temperature, etc.)
     * @param outputClass The expected return type
     * @return The parsed result of type O
     */
    fun <O> execute(
        initialMessages: List<Message>,
        initialToolCallbacks: List<ToolCallback>,
        chatOptions: ChatOptions,
        outputClass: Class<O>,
    ): ToolLoopResult<O>
}

data class ToolLoopResult<O>(
    val result: O,
    val conversationHistory: List<Message>,
    val totalIterations: Int,
    val injectedTools: List<ToolCallback>,  // All tools added during conversation
)
```

#### ToolProviderExtractor

Handles detection and extraction of tool providers:

```kotlin
package com.embabel.agent.spi.support.toolloop

/**
 * Extracts @LlmTool methods from @ToolProvider instances
 * and creates callable ToolCallbacks bound to those instances.
 */
class ToolProviderExtractor(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Check if an object is a @ToolProvider with @LlmTool methods.
     */
    fun isToolProvider(obj: Any): Boolean

    /**
     * Extract bound tool callbacks from a @ToolProvider instance.
     * 
     * @param instance The @ToolProvider instance
     * @return List of ToolCallbacks bound to this instance's @LlmTool methods
     */
    fun extractToolCallbacks(instance: Any): List<BoundMethodToolCallback>

    /**
     * Try to deserialize a tool result string and check if it's a @ToolProvider.
     * 
     * @param jsonResult The JSON string returned by a tool
     * @param expectedType The expected type (if known)
     * @return The deserialized object if it's a @ToolProvider, null otherwise
     */
    fun tryExtractToolProvider(
        jsonResult: String,
        expectedType: Class<*>?,
    ): Any?
}
```

#### BoundMethodToolCallback

A tool callback that invokes a method on a specific instance:

```kotlin
package com.embabel.agent.spi.support.toolloop

/**
 * A ToolCallback that invokes an @LlmTool method on a specific
 * @ToolProvider instance.
 */
class BoundMethodToolCallback(
    private val instance: Any,
    private val method: KFunction<*>,
    private val toolName: String,
    private val description: String,
    private val objectMapper: ObjectMapper,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition {
        // Generate JSON schema from method parameters
    }

    override fun call(toolInput: String): String {
        // Parse input, invoke method, serialize result
    }
}
```

---

## Detailed Design

### Tool Naming Strategy

When a `@ToolProvider` instance is discovered, its tools need unique names to avoid collisions (e.g., two Customer
instances).

**Format**: `{prefix}_{instanceId}_{methodName}`

**Examples**:

- `customer_c123_getAverageSpend`
- `customer_c456_getRecentOrders`
- `order_ord789_getLineItems`

**Implementation**:

```kotlin
fun generateToolName(
    instance: Any,
    method: KFunction<*>,
    annotation: LlmTool,
    providerAnnotation: ToolProvider,
): String {
    val prefix = providerAnnotation.prefix.ifEmpty {
        instance::class.simpleName?.lowercase() ?: "tool"
    }

    val instanceId = extractInstanceId(instance, providerAnnotation.instanceIdProperty)

    val methodName = annotation.name.ifEmpty { method.name }

    return "${prefix}_${instanceId}_$methodName"
}

fun extractInstanceId(instance: Any, propertyName: String): String {
    val property = instance::class.memberProperties
        .find { it.name == propertyName }
        ?: throw IllegalArgumentException(
            "@ToolProvider ${instance::class} missing instanceIdProperty: $propertyName"
        )

    return property.getter.call(instance)?.toString()
        ?: throw IllegalArgumentException("Instance ID cannot be null")
}
```

### The Tool Loop Algorithm

```kotlin
fun <O> execute(
    initialMessages: List<Message>,
    initialToolCallbacks: List<ToolCallback>,
    chatOptions: ChatOptions,
    outputClass: Class<O>,
): ToolLoopResult<O> {

    val conversationHistory = initialMessages.toMutableList()
    val availableTools = initialToolCallbacks.toMutableList()
    val injectedTools = mutableListOf<ToolCallback>()
    var iterations = 0

    while (iterations < maxIterations) {
        iterations++

        // 1. Build prompt with current tools
        val prompt = buildPrompt(conversationHistory, availableTools, chatOptions)

        // 2. Call LLM (single inference, no internal tool loop)
        val response = callLlmWithoutToolExecution(prompt)

        // 3. Add assistant message to history
        val assistantMessage = response.result.output
        conversationHistory.add(assistantMessage.toEmbabelMessage())

        // 4. Check if LLM wants to call tools
        val toolCalls = assistantMessage.toolCalls
        if (toolCalls.isNullOrEmpty()) {
            // No tool calls - LLM is done, parse final response
            val result = parseResult(assistantMessage.text, outputClass)
            return ToolLoopResult(
                result = result,
                conversationHistory = conversationHistory,
                totalIterations = iterations,
                injectedTools = injectedTools,
            )
        }

        // 5. Execute each tool call
        for (toolCall in toolCalls) {
            val callback = findToolCallback(availableTools, toolCall.name)
                ?: throw ToolNotFoundException(toolCall.name, availableTools.map { it.name })

            // 5a. Execute the tool
            val result = executeToolWithEvents(callback, toolCall)

            // 5b. Try to deserialize result for strategy inspection
            val resultObject = tryDeserialize(result, callback.expectedReturnType)

            // 5c. Evaluate injection strategy
            val context = ToolInjectionContext(
                conversationHistory = conversationHistory,
                currentTools = availableTools,
                lastToolCall = ToolCallResult(
                    toolName = toolCall.name,
                    toolInput = toolCall.arguments,
                    result = result,
                    resultObject = resultObject,
                ),
                iterationCount = iterations,
            )

            val newTools = injectionStrategy.evaluateToolResult(context)
            if (newTools.isNotEmpty()) {
                availableTools.addAll(newTools)
                injectedTools.addAll(newTools)

                logger.info(
                    "Strategy injected {} tools after {}: {}",
                    newTools.size,
                    toolCall.name,
                    newTools.map { it.name }
                )
            }

            // 5d. Add tool result to history
            conversationHistory.add(
                ToolResultMessage(
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    content = result,
                )
            )
        }

        // 6. Continue loop - LLM will see updated history and tools
    }

    throw MaxIterationsExceededException(maxIterations)
}

/**
 * Call the ChatModel without Spring AI's automatic tool execution.
 * We pass tool definitions for the LLM to see, but handle execution ourselves.
 */
private fun callLlmWithoutToolExecution(prompt: Prompt): ChatResponse {
    // The key is passing tools in the prompt/options but NOT using
    // ChatClient's .toolCallbacks() which triggers automatic execution
    return chatModel.call(prompt)
}
```

### Integration with ChatClientLlmOperations

Modify `ChatClientLlmOperations` to delegate to `EmbabelToolLoop` when tool provider discovery is enabled:

```kotlin
// In ChatClientLlmOperations

override fun <O> doTransform(
    messages: List<Message>,
    interaction: LlmInteraction,
    outputClass: Class<O>,
    llmRequestEvent: LlmRequestEvent<O>?,
): O {
    // Check if tool provider discovery is enabled
    if (interaction.enableToolProviderDiscovery) {
        return doTransformWithToolLoop(messages, interaction, outputClass, llmRequestEvent)
    }

    // Existing implementation using Spring AI's ChatClient
    return doTransformWithChatClient(messages, interaction, outputClass, llmRequestEvent)
}

private fun <O> doTransformWithToolLoop(
    messages: List<Message>,
    interaction: LlmInteraction,
    outputClass: Class<O>,
    llmRequestEvent: LlmRequestEvent<O>?,
): O {
    val llm = chooseLlm(interaction.llm)
    val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

    val toolLoop = EmbabelToolLoop(
        chatModel = llm.model,
        objectMapper = objectMapper,
        toolProviderExtractor = toolProviderExtractor,
        eventPublisher = llmRequestEvent?.agentProcess?.processContext,
        maxIterations = interaction.maxToolIterations ?: 20,
    )

    val promptContributions = buildPromptContributions(interaction, llm)
    val initialMessages = buildInitialMessages(promptContributions, messages)

    val result = toolLoop.execute(
        initialMessages = initialMessages,
        initialToolCallbacks = interaction.toolCallbacks,
        chatOptions = chatOptions,
        outputClass = outputClass,
    )

    return result.result
}
```

### PromptRunner API Changes

Add the method to enable tool provider discovery:

```kotlin
interface PromptRunner {
    // ... existing methods ...

    /**
     * Enable automatic discovery of tools from @ToolProvider objects.
     * 
     * When enabled, if a tool returns an object annotated with @ToolProvider,
     * its @LlmTool methods become available as tools in the same conversation.
     * 
     * This uses Embabel's own tool loop rather than Spring AI's internal loop,
     * giving full control over message history and tool injection.
     * 
     * @return A new PromptRunner with tool provider discovery enabled
     */
    fun withToolProviderDiscovery(): PromptRunner

    /**
     * Set maximum tool loop iterations.
     * Default is 20.
     */
    fun withMaxToolIterations(max: Int): PromptRunner
}
```

In `OperationContextPromptRunner`:

```kotlin
data class OperationContextPromptRunner(
    // ... existing fields ...
    private val toolProviderDiscoveryEnabled: Boolean = false,
    private val maxToolIterations: Int = 20,
) : StreamingPromptRunner {

    override fun withToolProviderDiscovery(): PromptRunner =
        copy(toolProviderDiscoveryEnabled = true)

    override fun withMaxToolIterations(max: Int): PromptRunner =
        copy(maxToolIterations = max)
}
```

---

## Message Types

Ensure Embabel's message types support tool calls properly:

```kotlin
// In com.embabel.chat

data class AssistantMessage(
    override val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    override val timestamp: Instant = Instant.now(),
) : Message

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,  // JSON string
)

data class ToolResultMessage(
    val toolCallId: String,
    val name: String,
    val content: String,  // JSON string result
    override val timestamp: Instant = Instant.now(),
) : Message
```

Conversion to/from Spring AI messages:

```kotlin
// In MessageConversions.kt

fun AssistantMessage.toSpringAiMessage(): org.springframework.ai.chat.messages.AssistantMessage {
    return org.springframework.ai.chat.messages.AssistantMessage(
        content,
        emptyMap(),
        toolCalls.map {
            org.springframework.ai.chat.messages.ToolCall(it.id, "function", it.name, it.arguments)
        }
    )
}

fun ToolResultMessage.toSpringAiMessage(): org.springframework.ai.chat.messages.ToolResponseMessage {
    return org.springframework.ai.chat.messages.ToolResponseMessage(
        listOf(
            org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                toolCallId, name, content
            )
        ),
        emptyMap()
    )
}

fun org.springframework.ai.chat.messages.AssistantMessage.toEmbabelMessage(): AssistantMessage {
    return AssistantMessage(
        content = this.text ?: "",
        toolCalls = this.toolCalls?.map {
            ToolCall(it.id(), it.name(), it.arguments())
        } ?: emptyList()
    )
}
```

---

## Event Emission

The tool loop should emit events for observability:

```kotlin
// New event types

data class ToolLoopIterationEvent(
    override val agentProcess: AgentProcess,
    val iteration: Int,
    val availableToolCount: Int,
    val discoveredProviderCount: Int,
) : ProcessEvent

data class ToolProviderDiscoveredEvent(
    override val agentProcess: AgentProcess,
    val providerClass: String,
    val instanceId: String,
    val exposedTools: List<String>,
) : ProcessEvent
```

Emit from the tool loop:

```kotlin
// In EmbabelToolLoop

if (provider != null) {
    val newTools = toolProviderExtractor.extractToolCallbacks(provider)
    availableTools.addAll(newTools)

    eventPublisher?.onProcessEvent(
        ToolProviderDiscoveredEvent(
            agentProcess = currentProcess,
            providerClass = provider::class.qualifiedName ?: "unknown",
            instanceId = extractInstanceId(provider),
            exposedTools = newTools.map { it.name },
        )
    )
}
```

---

## Error Handling

### ToolNotFoundException

```kotlin
class ToolNotFoundException(
    val requestedTool: String,
    val availableTools: List<String>,
) : RuntimeException(
    "Tool '$requestedTool' not found. Available tools: $availableTools"
)
```

### MaxIterationsExceededException

```kotlin
class MaxIterationsExceededException(
    val maxIterations: Int,
) : RuntimeException(
    "Tool loop exceeded maximum iterations ($maxIterations). " +
            "This may indicate a loop in tool calls or an overly complex task."
)
```

### InvalidToolProviderException

```kotlin
class InvalidToolProviderException(
    val providerClass: Class<*>,
    val reason: String,
) : RuntimeException(
    "@ToolProvider ${providerClass.name} is invalid: $reason"
)
```

---

## Usage Example

```kotlin
@Action
fun answerCustomerQuestion(ctx: ActionContext, question: String): Answer {
    return ctx.ai
        .withToolGroup("customer-search")
        .withToolProviderDiscovery()  // Enables entity-based tool injection
        .createObject<Answer>(question)
}

// The search tool
@ToolGroup("customer-search")
class CustomerSearchTools(private val customerRepo: CustomerRepository) {

    @Tool("Search for a customer by name")
    fun searchCustomer(name: String): Customer? {
        return customerRepo.findByName(name)
    }
}

// Domain entity - its methods become tools when returned
@ToolProvider
class Customer(val id: String, val name: String) {

    @LlmTool("Get average monthly spend for this customer")
    fun getAverageSpend(): Money = // ...

        @LlmTool("Get recent orders for this customer")
        fun getRecentOrders(limit: Int = 10): List<Order> = // ...
}
```

**Flow**:

```
User: "What's John Smith's average spend?"

1. LLM sees tools: [searchCustomer]
2. LLM calls: searchCustomer("John Smith")
3. Returns: Customer("c-123", "John Smith")
4. ToolProviderStrategy detects @ToolProvider, extracts @LlmTool methods
5. LLM now sees: [searchCustomer, customer_c123_getAverageSpend, customer_c123_getRecentOrders]
6. LLM calls: customer_c123_getAverageSpend()
7. Returns: Money(450)
8. LLM responds: "John Smith's average spend is $450/month"
```

---

## Edge Cases

### 1. Multiple Instances of Same Type

When two `Customer` instances are returned (e.g., search returns multiple results):

```kotlin
// Tool returns List<Customer>
@Tool("Search customers by name")
fun searchCustomers(name: String): List<Customer>
```

Each `Customer` gets its own namespaced tools:

- `customer_c123_getAverageSpend`
- `customer_c456_getAverageSpend`

The LLM can distinguish by ID in the tool name.

**Implementation**: When deserializing a `List<T>` where `T` is a `@ToolProvider`, extract tools from each element:

```kotlin
fun tryExtractToolProvider(jsonResult: String, expectedType: Class<*>?): List<Any> {
    // Handle both single objects and lists
    val parsed = objectMapper.readTree(jsonResult)

    if (parsed.isArray) {
        return parsed.mapNotNull { element ->
            tryDeserializeAsToolProvider(element)
        }
    }

    return listOfNotNull(tryDeserializeAsToolProvider(parsed))
}
```

### 2. Nested Tool Providers

When `Order` is also a `@ToolProvider` and is returned from `customer.getRecentOrders()`:

```kotlin
@ToolProvider
class Order(val id: String) {
    @LlmTool("Get line items")
    fun getLineItems(): List<LineItem>
}
```

The tool loop naturally handles this—when `getRecentOrders` returns `List<Order>`, each `Order` is detected and its
tools are added.

**Consideration**: This can lead to tool explosion. Consider:

- A maximum tool count limit
- A depth limit for nested providers
- Logging warnings when tool count grows large

### 3. Tool Provider Methods Return Tool Providers

When `customer.getRecentOrders()` returns `List<Order>` and `Order` is a `@ToolProvider`:

This is handled by the same mechanism—after each tool call, we check if the result is a `@ToolProvider`.

### 4. Stateful Tool Providers

If a `@ToolProvider` holds state that can change:

```kotlin
@ToolProvider
class ShoppingCart(val id: String) {
    private val items = mutableListOf<Item>()

    @LlmTool("Add item to cart")
    fun addItem(productId: String, quantity: Int): CartUpdate {
        items.add(Item(productId, quantity))
        return CartUpdate(success = true, itemCount = items.size)
    }

    @LlmTool("Get cart contents")
    fun getContents(): List<Item> = items.toList()
}
```

This works because we keep the actual instance alive—the `BoundMethodToolCallback` holds a reference to it.

### 5. Tool Provider with Dependencies

If the `@ToolProvider` needs injected dependencies:

```kotlin
@ToolProvider
class Customer(
    val id: String,
    private val orderRepository: OrderRepository,  // Needs injection
)
```

The tool that *creates* the `Customer` must inject these:

```kotlin
@ToolGroup("customer-search")
class CustomerSearchTools(
    private val customerRepository: CustomerRepository,
    private val orderRepository: OrderRepository,
) {
    @Tool("Find customer by name")
    fun findCustomer(name: String): Customer? {
        val data = customerRepository.findByName(name) ?: return null
        return Customer(
            id = data.id,
            name = data.name,
            orderRepository = orderRepository,  // Pass dependency
        )
    }
}
```

Alternatively, use a factory:

```kotlin
@Component
class CustomerFactory(
    private val orderRepository: OrderRepository,
    private val spendCalculator: SpendCalculator,
) {
    fun create(data: CustomerData): Customer {
        return Customer(data.id, data.name, orderRepository, spendCalculator)
    }
}
```

---

## Testing Strategy

### Unit Tests

1. **ToolProviderExtractor**
    - Correctly identifies `@ToolProvider` classes
    - Extracts `@LlmTool` methods
    - Generates correct tool names
    - Handles missing `instanceIdProperty`
    - Handles classes without `@LlmTool` methods

2. **BoundMethodToolCallback**
    - Correctly invokes instance methods
    - Serializes results to JSON
    - Handles method parameters
    - Handles exceptions from method invocation

3. **EmbabelToolLoop**
    - Executes simple conversation without tools
    - Executes tool calls correctly
    - Detects and registers tool providers
    - Respects max iterations
    - Handles tool not found errors

### Integration Tests

1. **End-to-End Tool Provider Discovery**
   ```kotlin
   @Test
   fun `should discover tools from returned entity`() {
       val result = promptRunner
           .withToolGroup("customer-search")
           .withToolProviderDiscovery()
           .createObject<Answer>("What is John Smith's average spend?")
       
       // Verify searchCustomer was called
       // Verify customer_xxx_getAverageSpend was called
       // Verify correct answer returned
   }
   ```

2. **Multiple Tool Providers**
   ```kotlin
   @Test
   fun `should handle multiple tool provider instances`() {
       // Search returns 2 customers
       // Both should have their tools exposed
       // LLM should be able to call tools on either
   }
   ```

3. **Nested Tool Providers**
   ```kotlin
   @Test
   fun `should discover nested tool providers`() {
       // Customer.getRecentOrders() returns List<Order>
       // Order is also a @ToolProvider
       // Order tools should become available
   }
   ```

### FakePromptRunner Support

Extend `FakePromptRunner` to support testing tool provider scenarios:

```kotlin
class FakePromptRunner {
    // ... existing ...

    /**
     * Simulate a tool provider being returned
     */
    fun expectToolProviderReturn(
        toolName: String,
        provider: Any,
    ): FakePromptRunner
}
```

---

## File Structure

```
embabel-agent-api/src/main/kotlin/com/embabel/agent/
├── api/
│   ├── annotation/
│   │   ├── ToolProvider.kt           # NEW - marks entity classes
│   │   └── LlmTool.kt                 # NEW - marks instance methods
│   └── common/
│       ├── PromptRunner.kt            # MODIFY - add withToolProviderDiscovery()
│       └── support/
│           └── OperationContextPromptRunner.kt  # MODIFY
├── spi/
│   ├── LlmInteraction.kt              # MODIFY - add toolProviderDiscoveryEnabled
│   ├── toolloop/                       # NEW PACKAGE
│   │   ├── EmbabelToolLoop.kt         # Core loop implementation
│   │   ├── ToolInjectionStrategy.kt   # Strategy interface
│   │   ├── ToolInjectionContext.kt    # Context for strategy decisions
│   │   ├── ToolLoopResult.kt          # Result wrapper
│   │   ├── ToolLoopExceptions.kt      # Custom exceptions
│   │   ├── ToolProviderStrategy.kt    # Entity discovery strategy
│   │   ├── ToolProviderExtractor.kt   # Extracts @LlmTool methods
│   │   └── BoundMethodToolCallback.kt # Invokes method on instance
│   └── support/
│       └── springai/
│           └── ChatClientLlmOperations.kt  # MODIFY - delegate to tool loop
└── chat/
    └── Message.kt                      # MODIFY - ensure ToolResultMessage exists

embabel-agent-api/src/test/kotlin/com/embabel/agent/
└── spi/toolloop/                       # NEW
    ├── EmbabelToolLoopTest.kt
    ├── ToolProviderStrategyTest.kt
    ├── ToolProviderExtractorTest.kt
    └── BoundMethodToolCallbackTest.kt
```

---

## Implementation Order

### Phase 1: Core Loop Infrastructure

1. Define `ToolInjectionStrategy` interface and `ToolInjectionContext`
2. Implement `EmbabelToolLoop` with strategy hook point
3. Add message type support (`ToolResultMessage`, conversions)
4. Unit tests with mock strategy and mock ChatModel

### Phase 2: Tool Provider Strategy

1. Create `@ToolProvider` and `@LlmTool` annotations
2. Implement `ToolProviderExtractor`
3. Implement `BoundMethodToolCallback`
4. Implement `ToolProviderStrategy`
5. Unit tests for all components

### Phase 3: PromptRunner Integration

1. Add `withToolProviderDiscovery()` to `PromptRunner`
2. Modify `OperationContextPromptRunner`
3. Modify `LlmInteraction` to carry flag
4. Modify `ChatClientLlmOperations` to delegate to `EmbabelToolLoop`

### Phase 4: Testing & Polish

1. Integration tests with real LLM
2. Add event emission
3. Update `FakePromptRunner` for testing
4. Documentation

---

## Open Questions

1. **Tool naming collisions**: If two `Customer` instances are returned with the same ID, how do we handle tool name
   collisions? Generate unique suffixes?

2. **Serialization hints**: When an entity returns, should the JSON include hints about available tools?
   ```json
   {
     "id": "c-123",
     "name": "John Smith",
     "_availableOperations": ["getAverageSpend", "getRecentOrders"]
   }
   ```

3. **Tool limits**: Should there be a maximum number of injected tools? LLM accuracy degrades with many tools.

4. **Parallel tool calls**: Some LLMs return multiple tool calls in one response. Currently we process sequentially and
   evaluate strategy after each. Should we batch?

5. **Streaming support**: How does this interact with streaming responses? Deferred for initial implementation.

6. **Duplicate detection**: If the same entity is returned multiple times, should its tools be re-added? Or tracked to
   avoid duplicates?

7. **Testing support**: How should `FakePromptRunner` simulate tool provider discovery for unit tests?

8. **Instance lifecycle**: When does the bound instance get garbage collected? Need to ensure it stays alive for the
   duration of the conversation.

---

## Summary

Dynamic Tool Injection provides a mechanism for adding tools to an active LLM conversation based on runtime conditions,
with Embabel owning the tool execution loop.

**Core Abstraction**: `ToolInjectionStrategy` - evaluated after each tool call to determine if new tools should be
injected. This interface allows for future extensibility (conditional unlocks, phase-based tools, etc.) but only
`ToolProviderStrategy` is implemented initially.

**Primary Use Case**: Domain entities (`@ToolProvider`) exposing instance methods (`@LlmTool`) as callable tools when
returned from other tools.

**Key Design Decisions**:

- Embabel owns the loop, not Spring AI
- Full conversation history preserved
- Simple opt-in via `withToolProviderDiscovery()`
- Strategy interface allows future extension without changing the loop

**Example Flow**:

```
searchCustomer("John") → Customer instance → customer_c123_getAverageSpend() available
```

This provides a foundation for sophisticated agent behaviors while maintaining Embabel's philosophy of deterministic,
type-safe, JVM-native AI development.