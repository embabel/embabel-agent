# Coding style

## General

The project uses Maven, Kotlin and Spring.

Follow the style of the code you read. Favor clarity.

Do not include blank lines within methods without good reason.

GOOD REASON: two groups of 5 lines each, with a blank line between them to indicate that they are distinct processes.

BAD REASON: one line/blank line/another line for no particular reason.

Don't put in licence headers as the build will do that.

Don't comment obvious things, inline or in type headers.
Comment only things that may be non-obvious. LLMs often comment
more than humans; don't do that. Variable, method and type naming should
be self-documenting as far as possible.

Use consistent naming in the Spring idiom.

Use the Spring idiom where possible.

Do not use builders. They are a design smell.
Use withers as in PromptRunner and other types.

Favor immutability.

Unless there is a specific reason not to, use the latest GA version of all dependencies.

Use @Nested classes in tests. Use `test complicated thing` instead of @DisplayName for test cases.
Do not couple tests too tightly to implementation.

In log statements, use placeholders for efficiency at all logging levels.
E.g. logger.info("{} {}", a, b) instead of logger.info("computed string").

Write new code in Kotlin rather than Java by default.
If a file is in Java, it should stay in Java.

If in any doubt, add Java tests and test fixtures to ensure that use from Java is idiomatic.

Make all classes internal if possible, unless they are clearly part of the public API.
Use @ApiStatus.Internal on internal classes that must be public for technical reasons.

## Kotlin

- Use Kotlin coding conventions and consistent formatting.
- Ensure that use from Java is idiomatic. For example, use @JvmOverloads
  to generate overloads for functions with default parameters if appropriate.
  Use @JvmStatic on companion object functions if appropriate
- Do not rely on extension functions. They will be unavailable from Java. Use static methods in companion objects with
  @JvmStatic.
- Use mockk for tests

## Java

- Use modern Java features like var, records, and switch expressions
- Use multiline strings rather than concatenation
- Use Mockito for tests

WRONG: String s = "a";
RIGHT: var s = "a";


