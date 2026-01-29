# Spring AI Integration

This package provides Spring AI integration for LLM message sending and tool calling.

## Provider Compatibility

The `SpringAiLlmMessageSender` and message converters handle various provider-specific behaviors for tool calling responses:

| Provider | Scenario | text content | toolCalls | Handling |
|----------|----------|--------------|-----------|----------|
| OpenAI | Normal text response | "Hello" | null/empty | `AssistantMessage` |
| OpenAI | Text + parallel tool calls | "Let me check" | [call1, call2] | `AssistantMessageWithToolCalls` |
| Anthropic | Text + tool use blocks | "I'll help" | [call] | `AssistantMessageWithToolCalls` |
| Bedrock | Empty text + tool calls | "" | [calls] | `AssistantMessageWithToolCalls` (empty parts) |
| Bedrock | Multiple generations (first empty, second has tool calls) | "" | [calls] | Searches all generations for tool calls |
| DeepSeek | null content + tool calls | null | [calls] | `AssistantMessageWithToolCalls` (null handled via `?: ""`) |
| All | Empty text, no tool calls | "" | [] | Exception (intentional - invalid response) |

## Key Implementation Details

### Multiple Generations (Bedrock and others)

Some providers may return multiple `Generation` objects in a single response. The `SpringAiLlmMessageSender.findGenerationWithToolCalls()` method handles this by **merging content from all generations**:

1. Collects all tool calls from all generations
2. Collects all non-empty text from all generations (joined with newlines)
3. Creates a single merged `AssistantMessage` with all content

This ensures we don't lose valuable content in scenarios like:

| Generation 1 | Generation 2 | Result |
|--------------|--------------|--------|
| text: "", toolCalls: [] | toolCalls: [call] | Uses merged (Bedrock noise case) |
| text: "I'll help" | toolCalls: [call] | Merges text + tool calls |
| toolCalls: [A] | toolCalls: [B] | Collects both tool calls |

See: https://github.com/embabel/embabel-agent/issues/1350

### Null/Empty Text Content

Some providers (Bedrock, DeepSeek) may return null or empty text content when there are tool calls. The `AssistantMessageWithToolCalls` class handles this correctly by not creating a `TextPart` for empty content:

```kotlin
parts = if (content.isNotEmpty()) listOf(TextPart(content)) else emptyList()
```

### Intentional Failures

An assistant message with no text content AND no tool calls is considered invalid and will throw an `IllegalArgumentException`. This enforces semantic correctness - the model must provide either content or tool calls.

## References

- [OpenAI Function Calling](https://platform.openai.com/docs/guides/function-calling)
- [Anthropic Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)
- [AWS Bedrock Claude Tool Use](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-anthropic-claude-messages-tool-use.html)
- [DeepSeek Function Calling](https://api-docs.deepseek.com/guides/function_calling)
