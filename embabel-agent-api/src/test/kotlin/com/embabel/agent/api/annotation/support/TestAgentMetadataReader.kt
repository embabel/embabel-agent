package com.embabel.agent.api.annotation.support

import com.embabel.agent.validation.DefaultAgentValidationManager
import com.embabel.agent.validation.NoPathToCompletionValidator

/**
 * Test helper for creating AgentMetadataReader instances for testing.
 *
 * This factory provides a controlled test setup with known dependencies
 * (including specific validators) to ensure consistent, isolated test environments.
 *
 * Usage in tests ensures that changes to dependency wiring can be made in one place,
 * improving maintainability and clarity.
 */
object TestAgentMetadataReader {
    fun create(): AgentMetadataReader {
        return AgentMetadataReader(
            actionMethodManager = DefaultActionMethodManager(),
            nameGenerator = MethodDefinedOperationNameGenerator(),
            agentValidationManager = DefaultAgentValidationManager(listOf(NoPathToCompletionValidator()))
        )
    }
}
