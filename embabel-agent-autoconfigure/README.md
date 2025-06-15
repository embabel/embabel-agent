# Embabel Agent Auto-Configuration

## Objective
Bootstrap beans defined in @**Configuration** artifacts for Spring Boot Application.

## Auto-Configuration Types

1. **AgentPlatformAutoConfiguration** - bootstraps Agent Platform Configuration,  Tools Group Configuration, and RAG Service Configuration


## Usage

Required dependency:

    **embabel-agent-starter-platform**

Example:
Include

    **@ImportAutoConfiguration(AgentPlatformAutoConfiguration.class)**

into Spring Boot Application