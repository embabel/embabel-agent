# Coding style

## General

The project uses Maven, Kotlin and Spring Boot.

Follow the style of the code you read. Favor clarity.

Do not include blank lines within methods without good reason.
GOOD REASON: two groups of 5 lines each, with a blank line between them to indicate that they are distinct processes.
BAD REASON: one line/blank line/another line for no particular reason.

Don't bother putting in licence headers, as build will do that.

Don't comment obvious things, inline or in type headers.
Comment only things that may be non-obvious. LLMs offer comment
more than humans; don't do that.

Use consistent naming in the Spring idiom.

Use the Spring idiom where possible.

Favor immutability.

Use the `Schema` and related annotations to add information to types passed over the wire in the REST application.
This will improve Swagger/OpenAPI documentation.

Unless there is a specific reason not to, use the latest GA version of all dependencies.

Use @Nested classes in tests. Use `test complicated thing` instead of @DisplayName for test cases.

In log statements, use placeholders for efficiency at all logging levels.
E.g. logger.info("{} {}", a, b) instead of logger.info("computed string").

Write new code in Kotlin rather than Java by default.
If a file is in Java, it should stay in Java.

If in any doubt, add Java tests and test fixtures to ensure that use from Java is idiomatic.

## Kotlin

- Use Kotlin coding conventions and consistent formatting.
- Ensure that use from Java is idiomatic. For example, use @JvmOverloads
  to generate overloads for functions with default parameters if appropriate.
  Use @JvmStatic on companion object functions if appropriate.
- Use mockk for tests.

## Java

- Use modern Java features like var, records, and switch expressions.
- Use multiline strings rather than concatenation.
- Use Mockito for tests.

WRONG: String s = "a";
RIGHT: var s = "a";


