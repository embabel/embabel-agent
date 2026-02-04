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
package com.embabel.agent.api.annotation.support.state;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.core.ActionInvocation;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for polymorphic @State interfaces with Java records.
 *
 * This reproduces a community-reported issue where actions in state implementations
 * fail to execute when the parent action returns the interface type.
 * <p>
 * The reported pattern was:
 * <pre>
 * @State interface Stage {}
 *
 * @Action
 * Stage evalRequest(Request request, Params params, Context ctx) {
 *     if (request.getLevels().length == 0) {
 *         return new NeedsData(request, params, ctx);
 *     } else {
 *         return new Ready(request, params, ctx);
 *     }
 * }
 *
 * @State record NeedsData(...) implements Stage {
 *     @Action Result getData() { ... }
 * }
 * </pre>
 */
public class PolymorphicStateInterfaceTest {

    private final AgentMetadataReader reader = new AgentMetadataReader();

    // Input types - mimics user's SelectAgentRequest, GeoFeatureParams, Ai
    public record SelectRequest(String content, String[] ridLevels) {}
    public record FeatureParams(String layerId) {}
    public record AiContext(String model) {}

    // Output types
    public record ProcessedResult(String output) {}
    public record RidLevelList(String[] levels) {}

    // State interface - plain marker interface like user's code
    @State
    public interface Stage {}

    // State implementation 1: needs more data (like user's RequestData)
    @State
    public record NeedsMoreData(
        SelectRequest request,
        FeatureParams params,
        AiContext ai
    ) implements Stage {

        @Action(description = "Request rid levels from user")
        public RidLevelList getRelatedRidLevels() {
            // In user's code this was: WaitFor.formSubmission("Need related rid levels", RidLevelList.class)
            // We simulate by just returning data
            return new RidLevelList(new String[]{"level1", "level2"});
        }

        @Action(description = "Adds rid levels and restarts search")
        public ReadyToProcess addRelatedRidLevels(RidLevelList ridLevelList) {
            return new ReadyToProcess(
                new SelectRequest(request.content(), ridLevelList.levels()),
                params,
                ai
            );
        }
    }

    // State implementation 2: ready to process (like user's StartSearch)
    @State
    public record ReadyToProcess(
        SelectRequest request,
        FeatureParams params,
        AiContext ai
    ) implements Stage {

        @AchievesGoal(description = "Search complete")
        @Action(description = "Creates search request and runs search")
        public ProcessedResult createSearchRequest() {
            return new ProcessedResult("Searched: " + request.content() +
                " with " + request.ridLevels().length + " levels" +
                " on layer " + params.layerId());
        }
    }

    // Agent that uses polymorphic states - matches user's pattern
    @com.embabel.agent.api.annotation.Agent(description = "Feature select agent")
    public static class FeatureSelectAgent {

        @Action(description = "Takes user request and checks if search can be run")
        public Stage evalRequest(SelectRequest request, FeatureParams params, AiContext ai) {
            if (request.ridLevels() == null || request.ridLevels().length == 0) {
                return new NeedsMoreData(request, params, ai);
            } else {
                return new ReadyToProcess(request, params, ai);
            }
        }
    }

    @Test
    @DisplayName("Agent metadata should include actions from all state implementations")
    public void agentMetadataShouldIncludeAllStateActions() {
        Agent agent = (Agent) reader.createAgentMetadata(new FeatureSelectAgent());

        var actionNames = agent.getActions().stream()
            .map(com.embabel.agent.core.Action::getName)
            .toList();

        assertTrue(actionNames.stream().anyMatch(n -> n.contains("evalRequest")),
            "Should have evalRequest action: " + actionNames);
        assertTrue(actionNames.stream().anyMatch(n -> n.contains("getRelatedRidLevels")),
            "Should have getRelatedRidLevels action from NeedsMoreData: " + actionNames);
        assertTrue(actionNames.stream().anyMatch(n -> n.contains("addRelatedRidLevels")),
            "Should have addRelatedRidLevels action from NeedsMoreData: " + actionNames);
        assertTrue(actionNames.stream().anyMatch(n -> n.contains("createSearchRequest")),
            "Should have createSearchRequest action from ReadyToProcess: " + actionNames);
    }

    @Test
    @DisplayName("Execution should work when returning ReadyToProcess directly")
    public void executionShouldWorkWhenReturningReadyToProcessDirectly() {
        Agent agent = (Agent) reader.createAgentMetadata(new FeatureSelectAgent());
        var platform = IntegrationTestUtils.dummyAgentPlatform();

        // Request with levels - should go directly to ReadyToProcess
        SelectRequest request = new SelectRequest("test content", new String[]{"level1"});
        FeatureParams params = new FeatureParams("layer-123");
        AiContext ai = new AiContext("gpt-4");

        AgentProcess process = platform.runAgentFrom(
            agent,
            new ProcessOptions(),
            Map.of("it", request, "params", params, "ai", ai)
        );

        var history = process.getHistory().stream()
            .map(com.embabel.agent.core.ActionInvocation::getActionName)
            .toList();

        assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus(),
            "Agent should complete when request has levels. History: " + history);
        assertTrue(history.stream().anyMatch(n -> n.contains("createSearchRequest")),
            "Should execute createSearchRequest action: " + history);
    }

    @Test
    @DisplayName("Execution should work when returning NeedsMoreData - polymorphic state")
    public void executionShouldWorkWhenReturningNeedsMoreData() {
        Agent agent = (Agent) reader.createAgentMetadata(new FeatureSelectAgent());
        var platform = IntegrationTestUtils.dummyAgentPlatform();

        // Request without levels - should go to NeedsMoreData first
        SelectRequest request = new SelectRequest("test content", new String[]{});
        FeatureParams params = new FeatureParams("layer-123");
        AiContext ai = new AiContext("gpt-4");

        AgentProcess process = platform.runAgentFrom(
            agent,
            new ProcessOptions(),
            Map.of("it", request, "params", params, "ai", ai)
        );

        var history = process.getHistory().stream()
            .map(com.embabel.agent.core.ActionInvocation::getActionName)
            .toList();

        // This is the failing case - the action in NeedsMoreData should execute
        assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus(),
            "Agent should complete when going through NeedsMoreData state. " +
            "History: " + history);
        assertTrue(history.stream().anyMatch(n -> n.contains("getRelatedRidLevels") || n.contains("addRelatedRidLevels")),
            "Should execute NeedsMoreData actions: " + history);
    }
}
