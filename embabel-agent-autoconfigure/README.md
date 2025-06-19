# Embabel Agent Auto-Configuration

## Objective
Bootstrap beans defined in @**Configuration** artifacts for Spring Boot Application.

## Auto-Configuration Types

1. **AgentPlatformAutoConfiguration** - bootstraps Agent Platform Configuration,  Tools Group Configuration, and RAG Service Configuration


## Direct Usage (not recommended)

Required dependency:

    **embabel-agent-starter-platform**

Example:
Include

    **@ImportAutoConfiguration(AgentPlatformAutoConfiguration.class)**

into Spring Boot Application

## Annotation-Driven Auto-Configuration

Example:

    @SpringBootApplication
    @EnableAgentShell
    //@EnableAgents("shell, starwars")
    public class AgentExampleApplication {....  }

List of Annotations:

1. EnableAgentShell
2. EnableAgentMCP
3. EnableAgentBedrock (as shell application)
4. EnableAgents - generic annotation; allows any mix of profiles

Note! - only single annotation is allowed.
