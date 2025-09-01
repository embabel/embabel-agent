# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Important

- ALL instructions within this document MUST BE FOLLOWED, these are not optional unless explicitly stated.
- ASK FOR CLARIFICATION If you are uncertain of anything within the document.
- DO NOT edit more code than you have to.
- DO NOT WASTE TOKENS, be succinct and concise.

## Coding Style

IMPORTANT: Adhere to coding style in `.embabel/coding-style.md` for coding style guidance.

## Working Approach

You should work test first.

IMPORTANT: You should build the most relevant project (closest pom.xml) with Maven before
presenting finished changes. Run the individual tests you have changed
or that are most relevant.

## Advice for Documentation in embabel-agent-docs

When working on documentation in the `embabel-agent-docs` module, follow these guidelines:

Use Asciidoctor syntax for writing documentation.

When asked to edit a particular .adoc file, look for a parallel file with a name prefixed with a . for instructions.

For example, if you are asked to edit `tools.adoc`, look for `.tools.adoc` for specific instructions about how to go
about it.
Thus you will be given a combination of user instructions such as ("edit tools.adoc to cover the FooBar tool in
FooBar.kt").
You will follow the user instruction but bear in mind the instructions in any .tools.adoc file.
If there is no .<filename>.adoc file, follow the general instructions in this document.

When writing documentation, ensure that you cover the following general instructions:

NEVER make up code unless asked. Code examples must come from the following repositories:

- This repository
- The `embabel-agent-examples` repository at https://github.com/embabel/embabel-agent-examples/
- The `java-agent-template` repository at https://github.com/embabel/java-agent-template/
- Other repositories the user specifically asks you to use.



