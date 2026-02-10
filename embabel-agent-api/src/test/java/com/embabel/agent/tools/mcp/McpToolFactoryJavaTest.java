/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.tools.mcp;

import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Java interoperability tests for McpToolFactory.
 * These tests verify that McpToolFactory methods can be used from Java with lambdas.
 */
class McpToolFactoryJavaTest {

    // Create factory with empty client list for testing API compatibility
    private final McpToolFactory factory = new McpToolFactory(Collections.emptyList());

    @Test
    void unfoldingAcceptsJavaLambda() {
        // This should compile and work - verifies Java lambda compatibility
        UnfoldingTool tool = factory.unfolding(
            "test_tool",
            "Test description",
            callback -> callback.getToolDefinition().name().startsWith("test_")
        );
        assertNotNull(tool);
    }

    @Test
    void unfoldingWithRemoveOnInvokeAcceptsJavaLambda() {
        UnfoldingTool tool = factory.unfolding(
            "test_tool",
            "Test description",
            callback -> callback.getToolDefinition().name().startsWith("test_"),
            false
        );
        assertNotNull(tool);
    }

    @Test
    void unfoldingByNameWorks() {
        UnfoldingTool tool = factory.unfoldingByName(
            "test_tool",
            "Test description",
            Set.of("tool1", "tool2", "tool3")
        );
        assertNotNull(tool);
    }

    @Test
    void unfoldingByNameWithRemoveOnInvokeWorks() {
        UnfoldingTool tool = factory.unfoldingByName(
            "test_tool",
            "Test description",
            Set.of("tool1", "tool2"),
            false
        );
        assertNotNull(tool);
    }
}
