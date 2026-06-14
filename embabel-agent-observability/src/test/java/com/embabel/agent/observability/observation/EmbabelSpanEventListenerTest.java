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
package com.embabel.agent.observability.observation;

import com.embabel.agent.api.event.AgentProcessCompletedEvent;
import com.embabel.agent.api.event.AgentProcessFailedEvent;
import com.embabel.agent.api.event.AgentProcessPausedEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent;
import com.embabel.agent.api.event.AgentProcessStuckEvent;
import com.embabel.agent.api.event.AgentProcessWaitingEvent;
import com.embabel.agent.api.event.ProcessKilledEvent;
import com.embabel.agent.api.event.DynamicAgentCreationEvent;
import com.embabel.agent.core.Agent;
import com.embabel.agent.api.event.EmbeddingInvocationEvent;
import com.embabel.agent.api.event.EmbeddingRequestEvent;
import com.embabel.agent.api.event.EmbeddingResponseEvent;
import com.embabel.agent.api.event.GoalAchievedEvent;
import com.embabel.agent.api.event.LlmInvocationEvent;
import com.embabel.agent.api.event.RankingChoiceCouldNotBeMadeEvent;
import com.embabel.agent.api.event.RankingChoiceMadeEvent;
import com.embabel.agent.api.event.ReplanRequestedEvent;
import com.embabel.agent.api.event.StateTransitionEvent;
import com.embabel.agent.api.event.ToolCallRequestEvent;
import com.embabel.agent.api.event.ToolCallResponseEvent;
import com.embabel.agent.api.event.ToolLoopCompletedEvent;
import com.embabel.agent.api.event.observation.ToolCallOutcomes;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.EarlyTermination;
import com.embabel.agent.core.EarlyTerminationPolicy;
import com.embabel.agent.core.EmbeddingInvocation;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.Usage;
import com.embabel.agent.event.AgentProcessRagEvent;
import com.embabel.agent.event.RagEvent;
import com.embabel.agent.event.RagResponseEvent;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.agent.rag.service.QualityMetrics;
import com.embabel.agent.rag.service.RagRequest;
import com.embabel.agent.rag.service.RagResponse;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.ai.model.EmbeddingServiceMetadata;
import com.embabel.common.ai.model.LlmMetadata;
import com.embabel.common.ai.model.PricingModel;
import com.embabel.common.core.types.Named;
import com.embabel.plan.Action;
import com.embabel.plan.Goal;
import com.embabel.plan.Plan;
import com.embabel.plan.WorldState;
import com.embabel.agent.api.common.ranking.Ranking;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the point-event span listener: events become instantaneous spans nested under the
 * current observation. Uses a recording handler.
 */
class EmbabelSpanEventListenerTest {

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    private ObservationRegistry registry;
    private RecordingHandler handler;
    private ObservabilityProperties properties;

    @BeforeEach
    void setUp() {
        registry = ObservationRegistry.create();
        handler = new RecordingHandler();
        registry.observationConfig().observationHandler(handler);
        properties = new ObservabilityProperties();
    }

    private EmbabelSpanEventListener listener() {
        return new EmbabelSpanEventListener(registry, properties);
    }

    private Map<String, String> kvOf(String spanName) {
        Observation.Context ctx = handler.stopped.stream()
                .filter(c -> spanName.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + spanName));
        return KeyValues.of(ctx.getLowCardinalityKeyValues())
                .and(ctx.getHighCardinalityKeyValues())
                .stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    private LlmInvocationEvent llmEvent() {
        AgentProcess process = mock(AgentProcess.class);
        LlmInvocation invocation = new LlmInvocation(
                LlmMetadata.create("gpt-4o", "openai"),
                new Usage(10, 20, null),
                null, Instant.now(), Duration.ZERO);
        return new LlmInvocationEvent(process, invocation, "interaction-3");
    }

    private EmbeddingInvocationEvent embeddingEvent() {
        AgentProcess process = mock(AgentProcess.class);
        EmbeddingServiceMetadata metadata = mock(EmbeddingServiceMetadata.class);
        lenient().when(metadata.getName()).thenReturn("text-embedding-3");
        EmbeddingInvocation invocation = new EmbeddingInvocation(
                metadata, new Usage(5, null, null), null, Instant.now(), Duration.ZERO);
        return new EmbeddingInvocationEvent(process, invocation, "embed-1");
    }

    private AgentProcessPlanFormulatedEvent planEvent() {
        Goal goal = mock(Goal.class);
        lenient().when(goal.getName()).thenReturn("done");
        Plan plan = mock(Plan.class);
        lenient().when(plan.getGoal()).thenReturn(goal);
        lenient().when(plan.getActions()).thenReturn(List.of());
        AgentProcess process = mock(AgentProcess.class);
        lenient().when(process.getId()).thenReturn("run-1");
        WorldState worldState = worldState("world: ready");
        AgentProcessPlanFormulatedEvent event = mock(AgentProcessPlanFormulatedEvent.class);
        lenient().when(event.getPlan()).thenReturn(plan);
        lenient().when(event.getWorldState()).thenReturn(worldState);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        return event;
    }

    private WorldState worldState(String info) {
        WorldState worldState = mock(WorldState.class);
        lenient().when(worldState.infoString(any(), anyInt())).thenReturn(info);
        return worldState;
    }

    private AgentProcessCompletedEvent completedEvent() {
        AgentProcess process = mock(AgentProcess.class);
        lenient().when(process.getId()).thenReturn("run-1");
        lenient().when(process.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        AgentProcessCompletedEvent event = mock(AgentProcessCompletedEvent.class);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        return event;
    }

    private DynamicAgentCreationEvent dynamicAgentEvent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("GeneratedAgent");
        DynamicAgentCreationEvent event = mock(DynamicAgentCreationEvent.class);
        lenient().when(event.getAgent()).thenReturn(agent);
        lenient().when(event.getBasis()).thenReturn("user asked for X");
        return event;
    }

    private ToolCallResponseEvent toolResponse(ToolGroupMetadata metadata) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        lenient().when(request.getTool()).thenReturn("myTool");
        lenient().when(request.getCorrelationId()).thenReturn("corr-1");
        lenient().when(request.getToolInput()).thenReturn("{\"q\":1}");
        lenient().when(request.getToolGroupMetadata()).thenReturn(metadata);
        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class);
        lenient().when(event.getRequest()).thenReturn(request);
        lenient().when(event.getRunningTime()).thenReturn(Duration.ofMillis(7));
        return event;
    }

    private AgentProcess processWithStatus(String id, AgentProcessStatusCode status) {
        AgentProcess process = mock(AgentProcess.class);
        lenient().when(process.getId()).thenReturn(id);
        lenient().when(process.getStatus()).thenReturn(status);
        return process;
    }

    @Nested
    @DisplayName("LLM and embedding invocations")
    class Invocations {

        @Test
        @DisplayName("LLM invocation becomes a span with model and token usage")
        void llmInvocationSpan() {
            listener().onProcessEvent(llmEvent());

            Map<String, String> kv = kvOf("embabel.llm.invocation");
            // Not a GenAI "generation": no gen_ai.operation.name, so exporters don't double-count
            // this call against the Spring AI ChatModel generation span. Model moves to embabel.llm.model.
            assertNull(kv.get("gen_ai.operation.name"));
            assertEquals("gpt-4o", kv.get("embabel.llm.model"));
            assertEquals("10", kv.get("gen_ai.usage.input_tokens"));
            assertEquals("20", kv.get("gen_ai.usage.output_tokens"));
            assertEquals("30", kv.get("gen_ai.usage.total_tokens"));
            assertEquals("interaction-3", kv.get("embabel.interaction.id"));
        }

        @Test
        @DisplayName("embedding invocation becomes a span with model and input tokens")
        void embeddingInvocationSpan() {
            listener().onProcessEvent(embeddingEvent());

            Map<String, String> kv = kvOf("embabel.embedding");
            assertEquals("embeddings", kv.get("gen_ai.operation.name"));
            assertEquals("text-embedding-3", kv.get("gen_ai.request.model"));
            assertEquals("5", kv.get("gen_ai.usage.input_tokens"));
        }

        @Test
        @DisplayName("invocation span nests under the current observation")
        void nestsUnderCurrentObservation() {
            Observation parent = Observation.createNotStarted("embabel.tool_loop", registry);
            parent.observe(() -> listener().onProcessEvent(llmEvent()));

            Observation.Context invocation = handler.stopped.stream()
                    .filter(c -> "embabel.llm.invocation".equals(c.getName()))
                    .findFirst().orElseThrow();
            assertTrue(invocation.getParentObservation() != null
                    && "embabel.tool_loop".equals(invocation.getParentObservation().getContextView().getName()));
        }

        @Test
        @DisplayName("trace-llm-calls=false suppresses the LLM invocation span")
        void llmFlagDisabled() {
            properties.setTraceLlmCalls(false);
            listener().onProcessEvent(llmEvent());
            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.llm.invocation".equals(c.getName())));
        }

        @Test
        @DisplayName("trace-embedding=false suppresses the embedding invocation span")
        void embeddingFlagDisabled() {
            properties.setTraceEmbedding(false);
            listener().onProcessEvent(embeddingEvent());
            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.embedding".equals(c.getName())));
        }

        @Test
        @DisplayName("LLM cost is recorded as embabel.llm.cost when pricing is known")
        void llmCostRecordedWhenPricingKnown() {
            AgentProcess process = mock(AgentProcess.class);
            LlmInvocation invocation = new LlmInvocation(
                    LlmMetadata.create("gpt-4o", "openai", null, PricingModel.usdPerToken(0.001, 0.002)),
                    new Usage(10, 20, null),
                    null, Instant.now(), Duration.ZERO);
            listener().onProcessEvent(new LlmInvocationEvent(process, invocation, "interaction-cost"));

            Map<String, String> kv = kvOf("embabel.llm.invocation");
            assertNotNull(kv.get("embabel.llm.cost"), "cost tag present when pricing yields a positive cost");
        }
    }

    @Nested
    @DisplayName("standalone embeddings (EmbeddingEventListener channel)")
    class StandaloneEmbeddings {

        private EmbeddingResponseEvent standaloneResponseEvent() {
            EmbeddingServiceMetadata metadata = mock(EmbeddingServiceMetadata.class);
            lenient().when(metadata.getName()).thenReturn("text-embedding-3");
            EmbeddingRequestEvent request = new EmbeddingRequestEvent(
                    metadata, List.of("hello"), "embed-standalone-1", Instant.now());
            return new EmbeddingResponseEvent(
                    request, new Usage(5, null, null), Duration.ofMillis(12), Instant.now());
        }

        @Test
        @DisplayName("standalone embedding response (no agent) becomes an embedding span")
        void standaloneEmbeddingSpan() {
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(null);
                listener().onEmbeddingEvent(standaloneResponseEvent());
            }

            Map<String, String> kv = kvOf("embabel.embedding");
            assertEquals("embeddings", kv.get("gen_ai.operation.name"));
            assertEquals("text-embedding-3", kv.get("gen_ai.request.model"));
            assertEquals("5", kv.get("gen_ai.usage.input_tokens"));
            assertEquals("embed-standalone-1", kv.get("embabel.interaction.id"));
        }

        @Test
        @DisplayName("when an agent process is active the agent channel owns the span, so no duplicate here")
        void noStandaloneSpanWhenAgentActive() {
            AgentProcess process = mock(AgentProcess.class);
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(process);
                listener().onEmbeddingEvent(standaloneResponseEvent());
            }

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.embedding".equals(c.getName())),
                    "no standalone embedding span when an agent process is active (avoids double span)");
        }

        @Test
        @DisplayName("trace-embedding=false suppresses the standalone embedding span")
        void standaloneFlagDisabled() {
            properties.setTraceEmbedding(false);
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(null);
                listener().onEmbeddingEvent(standaloneResponseEvent());
            }

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.embedding".equals(c.getName())));
        }

        @Test
        @DisplayName("a non-response embedding event (request/model-call) produces no span")
        void requestEventProducesNoSpan() {
            EmbeddingServiceMetadata metadata = mock(EmbeddingServiceMetadata.class);
            lenient().when(metadata.getName()).thenReturn("text-embedding-3");
            EmbeddingRequestEvent request = new EmbeddingRequestEvent(
                    metadata, List.of("hello"), "embed-1", Instant.now());
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(null);
                listener().onEmbeddingEvent(request);
            }

            assertTrue(handler.stopped.isEmpty(), "only EmbeddingResponseEvent yields a span");
        }

        @Test
        @DisplayName("standalone embedding cost is recorded when pricing is known")
        void standaloneCostRecordedWhenPricingKnown() {
            EmbeddingServiceMetadata metadata = mock(EmbeddingServiceMetadata.class);
            lenient().when(metadata.getName()).thenReturn("text-embedding-3");
            lenient().when(metadata.getPricingModel()).thenReturn(PricingModel.usdPer1MTokens(0.02, 0.0));
            EmbeddingRequestEvent request = new EmbeddingRequestEvent(
                    metadata, List.of("hello"), "embed-cost-1", Instant.now());
            EmbeddingResponseEvent response = new EmbeddingResponseEvent(
                    request, new Usage(1000, null, null), Duration.ofMillis(5), Instant.now());
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(null);
                listener().onEmbeddingEvent(response);
            }

            assertNotNull(kvOf("embabel.embedding").get("embabel.llm.cost"),
                    "cost tag present when pricing yields a positive cost");
        }

        @Test
        @DisplayName("no cost tag when the embedding service has no pricing model")
        void standaloneNoCostWhenPricingUnknown() {
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(null);
                // standaloneResponseEvent() uses a metadata mock whose getPricingModel() is null
                listener().onEmbeddingEvent(standaloneResponseEvent());
            }

            assertNull(kvOf("embabel.embedding").get("embabel.llm.cost"),
                    "no cost tag when pricing is unknown (cost is zero)");
        }

        @Test
        @DisplayName("NOOP registry produces no standalone embedding span and does not throw")
        void noopRegistryProducesNoStandaloneSpan() {
            EmbabelSpanEventListener noop = new EmbabelSpanEventListener(ObservationRegistry.NOOP, properties);
            try (MockedStatic<AgentProcess> ap = mockStatic(AgentProcess.class)) {
                ap.when(AgentProcess::get).thenReturn(null);
                noop.onEmbeddingEvent(standaloneResponseEvent());
            }

            assertTrue(handler.stopped.isEmpty());
        }
    }

    @Nested
    @DisplayName("planning and replanning")
    class Planning {

        @Test
        @DisplayName("plan formulated becomes a planning span with goal and action count")
        void planningSpan() {
            listener().onProcessEvent(planEvent());

            Map<String, String> kv = kvOf("embabel.planning");
            assertEquals("planning", kv.get("gen_ai.operation.name"));
            assertEquals("done", kv.get("embabel.plan.goal"));
            assertEquals("done", kv.get("embabel.plan.goal_short"));
            assertEquals("0", kv.get("embabel.plan.action_count"));
            assertEquals("false", kv.get("embabel.plan.is_replanning"));
            assertEquals("1", kv.get("embabel.plan.iteration"));
        }

        @Test
        @DisplayName("plan formulated carries the world state as input and the formatted plan steps as output")
        void planningWorldStateAndSteps() {
            Goal goal = mock(Goal.class);
            lenient().when(goal.getName()).thenReturn("done");
            Action a1 = mock(Action.class);
            lenient().when(a1.getName()).thenReturn("research");
            Action a2 = mock(Action.class);
            lenient().when(a2.getName()).thenReturn("write");
            Plan plan = mock(Plan.class);
            lenient().when(plan.getGoal()).thenReturn(goal);
            lenient().when(plan.getActions()).thenReturn(List.of(a1, a2));
            AgentProcess process = mock(AgentProcess.class);
            lenient().when(process.getId()).thenReturn("run-1");
            WorldState worldState = worldState("conditions: c1=true");
            AgentProcessPlanFormulatedEvent event = mock(AgentProcessPlanFormulatedEvent.class);
            lenient().when(event.getPlan()).thenReturn(plan);
            lenient().when(event.getWorldState()).thenReturn(worldState);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.planning");
            assertEquals("conditions: c1=true", kv.get("input.value"));
            assertEquals("1. research\n2. write\n-> Goal: done", kv.get("output.value"));
        }

        @Test
        @DisplayName("ready-to-plan becomes a planning.ready span carrying the world state as input")
        void readyToPlanSpan() {
            AgentProcess process = mock(AgentProcess.class);
            lenient().when(process.getId()).thenReturn("run-1");
            WorldState worldState = worldState("conditions: start");
            AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
            lenient().when(event.getWorldState()).thenReturn(worldState);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.planning.ready");
            assertEquals("planning", kv.get("gen_ai.operation.name"));
            assertEquals("conditions: start", kv.get("input.value"));
        }

        @Test
        @DisplayName("fully-qualified goal keeps the full name and exposes a readable short_name")
        void planningGoalShortName() {
            Goal goal = mock(Goal.class);
            lenient().when(goal.getName()).thenReturn("com.quantplsar.agent.compareResults");
            Plan plan = mock(Plan.class);
            lenient().when(plan.getGoal()).thenReturn(goal);
            lenient().when(plan.getActions()).thenReturn(List.of());
            AgentProcess process = mock(AgentProcess.class);
            lenient().when(process.getId()).thenReturn("run-1");
            WorldState worldState = worldState("world: ready");
            AgentProcessPlanFormulatedEvent event = mock(AgentProcessPlanFormulatedEvent.class);
            lenient().when(event.getPlan()).thenReturn(plan);
            lenient().when(event.getWorldState()).thenReturn(worldState);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.planning");
            assertEquals("com.quantplsar.agent.compareResults", kv.get("embabel.plan.goal"));
            assertEquals("compareResults", kv.get("embabel.plan.goal_short"));
        }

        @Test
        @DisplayName("planning iteration counter: first plan is not replanning, second is")
        void planningIterationCounter() {
            AgentProcessPlanFormulatedEvent event = planEvent();

            EmbabelSpanEventListener listener = listener();
            listener.onProcessEvent(event);
            listener.onProcessEvent(event);

            List<Map<String, String>> plans = handler.stopped.stream()
                    .filter(c -> "embabel.planning".equals(c.getName()))
                    .map(c -> KeyValues.of(c.getLowCardinalityKeyValues())
                            .and(c.getHighCardinalityKeyValues())
                            .stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue)))
                    .toList();
            assertEquals(2, plans.size());
            assertEquals("false", plans.get(0).get("embabel.plan.is_replanning"));
            assertEquals("1", plans.get(0).get("embabel.plan.iteration"));
            assertEquals("true", plans.get(1).get("embabel.plan.is_replanning"));
            assertEquals("2", plans.get(1).get("embabel.plan.iteration"));
        }

        @Test
        @DisplayName("replan requested becomes a span carrying the reason")
        void replanSpan() {
            ReplanRequestedEvent event = mock(ReplanRequestedEvent.class);
            lenient().when(event.getReason()).thenReturn("stuck, retrying with new plan");

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.replan");
            assertEquals("replan", kv.get("gen_ai.operation.name"));
            assertEquals("stuck, retrying with new plan", kv.get("embabel.replan.reason"));
        }

        @Test
        @DisplayName("trace-planning=false suppresses the planning span")
        void planningFlagDisabled() {
            properties.setTracePlanning(false);
            listener().onProcessEvent(planEvent());
            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.planning".equals(c.getName())));
        }
    }

    @Nested
    @DisplayName("RAG")
    class Rag {

        @Test
        @DisplayName("RAG response becomes a rag span with service, query and result count")
        void ragSpan() {
            RagRequest request = mock(RagRequest.class);
            lenient().when(request.getQuery()).thenReturn("what is x");
            RagResponse response = mock(RagResponse.class);
            lenient().when(response.getService()).thenReturn("vec-store");
            lenient().when(response.getRequest()).thenReturn(request);
            lenient().when(response.getResults()).thenReturn(java.util.Collections.emptyList());
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.rag");
            assertEquals("rag", kv.get("gen_ai.operation.name"));
            assertEquals("vec-store", kv.get("embabel.rag.service"));
            assertEquals("what is x", kv.get("embabel.rag.query"));
            assertEquals("0", kv.get("embabel.rag.result_count"));
        }

        @Test
        @DisplayName("a long RAG query is truncated to max-attribute-length")
        void ragQueryTruncated() {
            properties.setMaxAttributeLength(10);
            RagRequest request = mock(RagRequest.class);
            lenient().when(request.getQuery()).thenReturn("0123456789ABCDEF");
            RagResponse response = mock(RagResponse.class);
            lenient().when(response.getService()).thenReturn("vec-store");
            lenient().when(response.getRequest()).thenReturn(request);
            lenient().when(response.getResults()).thenReturn(java.util.Collections.emptyList());
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            assertEquals("0123456789...", kvOf("embabel.rag").get("embabel.rag.query"));
        }

        @Test
        @DisplayName("trace-rag=false suppresses the rag span")
        void ragFlagDisabled() {
            properties.setTraceRag(false);
            RagResponse response = mock(RagResponse.class);
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.rag".equals(c.getName())));
        }

        @Test
        @DisplayName("a RAG event whose inner event is not a response produces no span")
        void ragNonResponseInnerEventProducesNoSpan() {
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(mock(RagEvent.class));

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.isEmpty(), "only RagResponseEvent inner events yield a span");
        }

        @Test
        @DisplayName("RAG span carries request params (top_k, similarity_threshold) and top score")
        void ragRequestParamsAndTopScore() {
            RagRequest request = mock(RagRequest.class);
            lenient().when(request.getQuery()).thenReturn("what is x");
            lenient().when(request.getTopK()).thenReturn(8);
            lenient().when(request.getSimilarityThreshold()).thenReturn(0.7);
            SimilarityResult<?> r1 = mock(SimilarityResult.class);
            lenient().when(r1.getScore()).thenReturn(0.42);
            SimilarityResult<?> r2 = mock(SimilarityResult.class);
            lenient().when(r2.getScore()).thenReturn(0.91);
            RagResponse response = mock(RagResponse.class);
            lenient().when(response.getService()).thenReturn("vec-store");
            lenient().when(response.getRequest()).thenReturn(request);
            lenient().doReturn(java.util.List.of(r1, r2)).when(response).getResults();
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.rag");
            assertEquals("8", kv.get("embabel.rag.top_k"));
            assertEquals("0.7", kv.get("embabel.rag.similarity_threshold"));
            assertEquals("2", kv.get("embabel.rag.result_count"));
            assertEquals("0.91", kv.get("embabel.rag.top_score"));
        }

        @Test
        @DisplayName("RAG span carries RAGAS quality metrics when present")
        void ragQualityMetrics() {
            RagRequest request = mock(RagRequest.class);
            lenient().when(request.getQuery()).thenReturn("what is x");
            QualityMetrics metrics = new QualityMetrics(0.9, 0.8, 0.7, 0.6, 0.5, 0.7);
            RagResponse response = mock(RagResponse.class);
            lenient().when(response.getService()).thenReturn("vec-store");
            lenient().when(response.getRequest()).thenReturn(request);
            lenient().when(response.getResults()).thenReturn(java.util.Collections.emptyList());
            lenient().when(response.getQualityMetrics()).thenReturn(metrics);
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.rag");
            assertEquals("0.9", kv.get("embabel.rag.faithfulness"));
            assertEquals("0.8", kv.get("embabel.rag.answer_relevancy"));
            assertEquals("0.7", kv.get("embabel.rag.context_precision"));
            assertEquals("0.6", kv.get("embabel.rag.context_recall"));
            assertEquals("0.5", kv.get("embabel.rag.context_relevancy"));
            assertNotNull(kv.get("embabel.rag.ragas_score"));
        }

        @Test
        @DisplayName("RAG span omits quality metrics and top score when absent")
        void ragNoMetricsNoResults() {
            RagRequest request = mock(RagRequest.class);
            lenient().when(request.getQuery()).thenReturn("what is x");
            RagResponse response = mock(RagResponse.class);
            lenient().when(response.getService()).thenReturn("vec-store");
            lenient().when(response.getRequest()).thenReturn(request);
            lenient().when(response.getResults()).thenReturn(java.util.Collections.emptyList());
            lenient().when(response.getQualityMetrics()).thenReturn(null);
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.rag");
            assertNull(kv.get("embabel.rag.top_score"));
            assertNull(kv.get("embabel.rag.faithfulness"));
            assertNull(kv.get("embabel.rag.ragas_score"));
        }
    }

    @Nested
    @DisplayName("ranking (platform events)")
    class RankingEvents {

        @Test
        @DisplayName("ranking choice becomes a ranking span with choice and score")
        void rankingSpan() {
            Named choice = mock(Named.class);
            lenient().when(choice.getName()).thenReturn("WeatherAgent");
            @SuppressWarnings("unchecked")
            Ranking<?> ranking = mock(Ranking.class);
            lenient().when(((Ranking) ranking).getMatch()).thenReturn(choice);
            lenient().when(ranking.getScore()).thenReturn(0.92);
            @SuppressWarnings("unchecked")
            RankingChoiceMadeEvent<?> event = mock(RankingChoiceMadeEvent.class);
            lenient().when(((RankingChoiceMadeEvent) event).getChoice()).thenReturn(ranking);
            org.mockito.Mockito.doReturn(List.of(new Object(), new Object())).when(event).getChoices();

            listener().onPlatformEvent(event);

            Map<String, String> kv = kvOf("embabel.ranking");
            assertEquals("ranking", kv.get("gen_ai.operation.name"));
            assertEquals("WeatherAgent", kv.get("embabel.ranking.choice"));
            assertEquals("2", kv.get("embabel.ranking.option_count"));
            assertEquals("0.92", kv.get("embabel.ranking.score"));
        }

        @Test
        @DisplayName("trace-ranking=false suppresses the ranking span")
        void rankingFlagDisabled() {
            properties.setTraceRanking(false);
            RankingChoiceMadeEvent<?> event = mock(RankingChoiceMadeEvent.class);

            listener().onPlatformEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.ranking".equals(c.getName())));
        }

        @Test
        @DisplayName("ranking with no choice becomes a span marked errored")
        void rankingCouldNotBeMadeSpan() {
            RankingChoiceCouldNotBeMadeEvent<?> event = mock(RankingChoiceCouldNotBeMadeEvent.class);
            lenient().when(((RankingChoiceCouldNotBeMadeEvent) event).getType()).thenReturn(String.class);
            lenient().when(event.getConfidenceCutOff()).thenReturn(0.7);
            org.mockito.Mockito.doReturn(List.of(new Object(), new Object())).when(event).getChoices();

            listener().onPlatformEvent(event);

            Observation.Context ctx = handler.stopped.stream()
                    .filter(c -> "embabel.ranking".equals(c.getName()))
                    .findFirst().orElseThrow();
            Map<String, String> kv = KeyValues.of(ctx.getLowCardinalityKeyValues())
                    .and(ctx.getHighCardinalityKeyValues())
                    .stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
            assertEquals("none", kv.get("embabel.ranking.choice"));
            assertEquals("String", kv.get("embabel.ranking.type"));
            assertEquals("2", kv.get("embabel.ranking.option_count"));
            assertEquals("0.7", kv.get("embabel.ranking.confidence_cutoff"));
            assertTrue(ctx.getError() != null, "no-choice ranking span should be marked errored");
        }
    }

    @Nested
    @DisplayName("state transitions, lifecycle and goals")
    class StateAndLifecycle {

        @Test
        @DisplayName("state transition becomes a span with from/to state types")
        void stateTransitionSpan() {
            StateTransitionEvent event = mock(StateTransitionEvent.class);
            lenient().when(event.getNewState()).thenReturn("toState");
            lenient().when(event.getPreviousState()).thenReturn(null);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.state_transition");
            assertEquals("state_transition", kv.get("gen_ai.operation.name"));
            assertEquals("String", kv.get("embabel.state.to"));
            assertEquals("none", kv.get("embabel.state.from"));
        }

        @Test
        @DisplayName("lifecycle event becomes a span carrying the final state")
        void lifecycleSpan() {
            listener().onProcessEvent(completedEvent());

            Map<String, String> kv = kvOf("embabel.lifecycle");
            assertEquals("lifecycle", kv.get("gen_ai.operation.name"));
            assertEquals("COMPLETED", kv.get("embabel.lifecycle.state"));
        }

        @Test
        @DisplayName("trace-lifecycle-states=false suppresses the lifecycle span")
        void lifecycleFlagDisabled() {
            properties.setTraceLifecycleStates(false);
            listener().onProcessEvent(completedEvent());
            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.lifecycle".equals(c.getName())));
        }

        @Test
        @DisplayName("goal achieved span keeps the full goal name and adds a short name")
        void goalAchievedSpan() {
            Goal goal = mock(Goal.class);
            lenient().when(goal.getName()).thenReturn("com.example.WizardAgent.answerQuestion");
            AgentProcess process = mock(AgentProcess.class);
            lenient().when(process.lastResult()).thenReturn("the answer");
            WorldState worldState = worldState("blackboard: UserInput, Answer");
            GoalAchievedEvent event = mock(GoalAchievedEvent.class);
            lenient().when(event.getGoal()).thenReturn(goal);
            lenient().when(event.getWorldState()).thenReturn(worldState);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.goal");
            assertEquals("goal_achieved", kv.get("gen_ai.operation.name"));
            assertEquals("com.example.WizardAgent.answerQuestion", kv.get("embabel.goal.name"));
            assertEquals("answerQuestion", kv.get("embabel.goal.short_name"));
            assertEquals("the answer", kv.get("embabel.goal.result"));
            assertEquals("blackboard: UserInput, Answer", kv.get("input.value"));
        }

        @Test
        @DisplayName("trace-state-transitions=false suppresses the state transition span")
        void stateTransitionFlagDisabled() {
            properties.setTraceStateTransitions(false);
            StateTransitionEvent event = mock(StateTransitionEvent.class);
            lenient().when(event.getNewState()).thenReturn("toState");

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.state_transition".equals(c.getName())));
        }

        @Test
        @DisplayName("failed run becomes a lifecycle span carrying FAILED")
        void failedLifecycleSpan() {
            listener().onProcessEvent(
                    new AgentProcessFailedEvent(processWithStatus("run-1", AgentProcessStatusCode.FAILED)));

            Map<String, String> kv = kvOf("embabel.lifecycle");
            assertEquals("FAILED", kv.get("embabel.lifecycle.state"));
        }

        @Test
        @DisplayName("waiting, paused and stuck each become a lifecycle span with their status")
        void intermediateLifecycleSpans() {
            EmbabelSpanEventListener listener = listener();
            listener.onProcessEvent(new AgentProcessWaitingEvent(processWithStatus("r", AgentProcessStatusCode.WAITING)));
            listener.onProcessEvent(new AgentProcessPausedEvent(processWithStatus("r", AgentProcessStatusCode.PAUSED)));
            listener.onProcessEvent(new AgentProcessStuckEvent(processWithStatus("r", AgentProcessStatusCode.STUCK)));

            List<String> states = handler.stopped.stream()
                    .filter(c -> "embabel.lifecycle".equals(c.getName()))
                    .map(c -> KeyValues.of(c.getLowCardinalityKeyValues()).stream()
                            .filter(k -> "embabel.lifecycle.state".equals(k.getKey()))
                            .map(KeyValue::getValue).findFirst().orElse(null))
                    .toList();
            assertEquals(List.of("WAITING", "PAUSED", "STUCK"), states);
        }

        @Test
        @DisplayName("trace-lifecycle-states=false suppresses the goal span (shared gate)")
        void goalSuppressedWhenLifecycleDisabled() {
            properties.setTraceLifecycleStates(false);
            Goal goal = mock(Goal.class);
            lenient().when(goal.getName()).thenReturn("com.example.A.answer");
            AgentProcess process = mock(AgentProcess.class);
            GoalAchievedEvent event = mock(GoalAchievedEvent.class);
            lenient().when(event.getGoal()).thenReturn(goal);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.goal".equals(c.getName())));
        }

        @Test
        @DisplayName("process killed emits a KILLED lifecycle span and resets the plan iteration counter")
        void killedEmitsLifecycleSpanAndResetsIterations() {
            EmbabelSpanEventListener listener = listener();
            AgentProcessPlanFormulatedEvent plan = planEvent(); // run-1
            listener.onProcessEvent(plan); // iteration 1
            listener.onProcessEvent(plan); // iteration 2 (replanning)
            listener.onProcessEvent(
                    new ProcessKilledEvent(processWithStatus("run-1", AgentProcessStatusCode.KILLED)));
            listener.onProcessEvent(plan); // counter reset -> iteration 1 again

            Map<String, String> kv = kvOf("embabel.lifecycle");
            assertEquals("KILLED", kv.get("embabel.lifecycle.state"),
                    "killed produces a lifecycle span carrying KILLED");

            List<Map<String, String>> plans = handler.stopped.stream()
                    .filter(c -> "embabel.planning".equals(c.getName()))
                    .map(c -> KeyValues.of(c.getLowCardinalityKeyValues())
                            .and(c.getHighCardinalityKeyValues())
                            .stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue)))
                    .toList();
            Map<String, String> lastPlan = plans.get(plans.size() - 1);
            assertEquals("1", lastPlan.get("embabel.plan.iteration"),
                    "iteration counter restarts after the run is killed");
            assertEquals("false", lastPlan.get("embabel.plan.is_replanning"));
        }

        @Test
        @DisplayName("early termination maps the process status onto the lifecycle span state")
        void earlyTerminationEmitsLifecycleSpan() {
            EarlyTerminationPolicy policy = mock(EarlyTerminationPolicy.class);
            // Listener-mapping test: with the process reporting TERMINATED, recordLifecycle must copy
            // getStatus() into embabel.lifecycle.state (parallel to the FAILED/KILLED/WAITING cases).
            // The core guarantee that the status is already TERMINATED when the event fires is covered
            // by AbstractAgentProcessTerminationStatusOrderingTest, not here (the process is mocked).
            EarlyTermination event = new EarlyTermination(
                    processWithStatus("run-1", AgentProcessStatusCode.TERMINATED), true, "budget exceeded", policy);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.lifecycle");
            assertEquals("TERMINATED", kv.get("embabel.lifecycle.state"));
        }
    }

    @Nested
    @DisplayName("dynamic agent creation")
    class DynamicAgent {

        @Test
        @DisplayName("dynamic agent creation becomes a span with agent name")
        void dynamicAgentCreationSpan() {
            listener().onPlatformEvent(dynamicAgentEvent());

            Map<String, String> kv = kvOf("embabel.dynamic_agent_creation");
            assertEquals("dynamic_agent_creation", kv.get("gen_ai.operation.name"));
            assertEquals("GeneratedAgent", kv.get("embabel.agent.name"));
            assertEquals("user asked for X", kv.get("embabel.dynamic_agent.basis"));
        }

        @Test
        @DisplayName("trace-dynamic-agent-creation=false suppresses the span")
        void dynamicAgentCreationFlagDisabled() {
            properties.setTraceDynamicAgentCreation(false);
            listener().onPlatformEvent(dynamicAgentEvent());
            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.dynamic_agent_creation".equals(c.getName())));
        }
    }

    @Nested
    @DisplayName("tool calls")
    class ToolCalls {

        @Test
        @DisplayName("successful tool call becomes a span with metadata, duration and result")
        void toolCallSuccessSpan() {
            ToolCallResponseEvent event = toolResponse(null);
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(event)).thenReturn(null);
                outcomes.when(() -> ToolCallOutcomes.resultText(event)).thenReturn("tool output");
                listener().onProcessEvent(event);
            }

            Map<String, String> kv = kvOf("embabel.tool");
            assertEquals("execute_tool", kv.get("gen_ai.operation.name"));
            assertEquals("myTool", kv.get("gen_ai.tool.name"));
            assertEquals("function", kv.get("gen_ai.tool.type"));
            assertEquals("myTool", kv.get("embabel.tool.name"));
            assertEquals("corr-1", kv.get("embabel.tool.correlation_id"));
            assertEquals("success", kv.get("embabel.tool.status"));
            assertEquals("7", kv.get("embabel.tool.duration_ms"));
            assertEquals("tool output", kv.get("gen_ai.tool.call.result"));
            assertNotNull(kv.get("gen_ai.tool.call.arguments"));
        }

        @Test
        @DisplayName("tool group metadata is recorded")
        void toolCallGroupMetadata() {
            ToolGroupMetadata metadata = mock(ToolGroupMetadata.class);
            lenient().when(metadata.getName()).thenReturn("web");
            lenient().when(metadata.getRole()).thenReturn("search");
            lenient().when(metadata.getDescription()).thenReturn("Web search tools");
            ToolCallResponseEvent event = toolResponse(metadata);
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(event)).thenReturn(null);
                outcomes.when(() -> ToolCallOutcomes.resultText(event)).thenReturn("ok");
                listener().onProcessEvent(event);
            }

            Map<String, String> kv = kvOf("embabel.tool");
            assertEquals("web", kv.get("embabel.tool.group.name"));
            assertEquals("search", kv.get("embabel.tool.group.role"));
            assertEquals("Web search tools", kv.get("gen_ai.tool.description"));
        }

        @Test
        @DisplayName("failed tool call records error status and marks the span errored")
        void toolCallErrorSpan() {
            ToolCallResponseEvent event = toolResponse(null);
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(event))
                        .thenReturn(new IllegalStateException("boom"));
                listener().onProcessEvent(event);
            }

            Observation.Context ctx = handler.stopped.stream()
                    .filter(c -> "embabel.tool".equals(c.getName()))
                    .findFirst().orElseThrow();
            Map<String, String> kv = KeyValues.of(ctx.getLowCardinalityKeyValues())
                    .and(ctx.getHighCardinalityKeyValues())
                    .stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
            assertEquals("error", kv.get("embabel.tool.status"));
            assertEquals("IllegalStateException", kv.get("embabel.tool.error.type"));
            assertEquals("boom", kv.get("embabel.tool.error.message"));
            assertNotNull(ctx.getError(), "errored tool call should attach the throwable to the span");
        }

        @Test
        @DisplayName("trace-tool-calls=false suppresses the tool span")
        void toolCallFlagDisabled() {
            properties.setTraceToolCalls(false);
            ToolCallResponseEvent event = toolResponse(null);

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.tool".equals(c.getName())));
        }

        @Test
        @DisplayName("placeholder correlation id '-' is not recorded")
        void toolCallOmitsPlaceholderCorrelationId() {
            ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
            lenient().when(request.getTool()).thenReturn("myTool");
            lenient().when(request.getCorrelationId()).thenReturn("-");
            lenient().when(request.getToolInput()).thenReturn("{}");
            ToolCallResponseEvent event = mock(ToolCallResponseEvent.class);
            lenient().when(event.getRequest()).thenReturn(request);
            lenient().when(event.getRunningTime()).thenReturn(Duration.ofMillis(1));
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(event)).thenReturn(null);
                outcomes.when(() -> ToolCallOutcomes.resultText(event)).thenReturn("ok");
                listener().onProcessEvent(event);
            }

            Map<String, String> kv = kvOf("embabel.tool");
            assertNull(kv.get("embabel.tool.correlation_id"), "'-' placeholder correlation id is not recorded");
        }

        @Test
        @DisplayName("null tool input omits the arguments tag")
        void toolCallOmitsArgumentsWhenInputNull() {
            ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
            lenient().when(request.getTool()).thenReturn("myTool");
            lenient().when(request.getCorrelationId()).thenReturn("corr-1");
            lenient().when(request.getToolInput()).thenReturn(null);
            ToolCallResponseEvent event = mock(ToolCallResponseEvent.class);
            lenient().when(event.getRequest()).thenReturn(request);
            lenient().when(event.getRunningTime()).thenReturn(Duration.ofMillis(1));
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(event)).thenReturn(null);
                outcomes.when(() -> ToolCallOutcomes.resultText(event)).thenReturn("ok");
                listener().onProcessEvent(event);
            }

            Map<String, String> kv = kvOf("embabel.tool");
            assertNull(kv.get("gen_ai.tool.call.arguments"), "no arguments tag when tool input is null");
        }
    }

    @Nested
    @DisplayName("tool loop completion")
    class ToolLoop {

        @Test
        @DisplayName("tool loop completion becomes a span with total iterations and replan flag")
        void toolLoopCompletedSpan() {
            ToolLoopCompletedEvent event = mock(ToolLoopCompletedEvent.class);
            lenient().when(event.getInteractionId()).thenReturn("interaction-3");
            lenient().when(event.getTotalIterations()).thenReturn(3);
            lenient().when(event.getReplanRequested()).thenReturn(true);
            lenient().when(event.getRunningTime()).thenReturn(Duration.ofMillis(42));

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.tool_loop.completed");
            assertEquals("tool_loop", kv.get("gen_ai.operation.name"));
            assertEquals("3", kv.get("embabel.tool_loop.total_iterations"));
            assertEquals("true", kv.get("embabel.tool_loop.replan_requested"));
            assertEquals("interaction-3", kv.get("embabel.interaction.id"));
            assertEquals("42", kv.get("embabel.tool_loop.duration_ms"));
        }

        @Test
        @DisplayName("trace-tool-loop=false suppresses tool loop completion span")
        void toolLoopCompletedFlagDisabled() {
            properties.setTraceToolLoop(false);
            ToolLoopCompletedEvent event = mock(ToolLoopCompletedEvent.class);
            lenient().when(event.getInteractionId()).thenReturn("i-1");
            lenient().when(event.getRunningTime()).thenReturn(Duration.ZERO);

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.tool_loop.completed".equals(c.getName())));
        }

        @Test
        @DisplayName("trace-tool-loop-completed=false suppresses only the completion point span")
        void toolLoopCompletedPointFlagDisabled() {
            properties.setTraceToolLoopCompleted(false);
            ToolLoopCompletedEvent event = mock(ToolLoopCompletedEvent.class);
            lenient().when(event.getInteractionId()).thenReturn("i-1");
            lenient().when(event.getRunningTime()).thenReturn(Duration.ZERO);

            listener().onProcessEvent(event);

            assertTrue(handler.stopped.stream().noneMatch(c -> "embabel.tool_loop.completed".equals(c.getName())));
        }
    }

    @Nested
    @DisplayName("content capture gating (capture-message-content=false)")
    class ContentCaptureGating {

        @BeforeEach
        void disableContentCapture() {
            properties.setCaptureMessageContent(false);
        }

        @Test
        @DisplayName("tool call omits arguments and result but keeps metadata")
        void toolCallOmitsContent() {
            ToolCallResponseEvent event = toolResponse(null);
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(event)).thenReturn(null);
                outcomes.when(() -> ToolCallOutcomes.resultText(event)).thenReturn("tool output");
                listener().onProcessEvent(event);
            }

            Map<String, String> kv = kvOf("embabel.tool");
            assertNull(kv.get("gen_ai.tool.call.arguments"));
            assertNull(kv.get("gen_ai.tool.call.result"));
            // Metadata stays.
            assertEquals("myTool", kv.get("gen_ai.tool.name"));
            assertEquals("success", kv.get("embabel.tool.status"));
            assertEquals("7", kv.get("embabel.tool.duration_ms"));
        }

        @Test
        @DisplayName("RAG omits the query but keeps service and counts")
        void ragOmitsQuery() {
            RagRequest request = mock(RagRequest.class);
            lenient().when(request.getQuery()).thenReturn("what is x");
            RagResponse response = mock(RagResponse.class);
            lenient().when(response.getService()).thenReturn("vec-store");
            lenient().when(response.getRequest()).thenReturn(request);
            lenient().when(response.getResults()).thenReturn(java.util.Collections.emptyList());
            RagResponseEvent ragResponseEvent = mock(RagResponseEvent.class);
            lenient().when(ragResponseEvent.getRagResponse()).thenReturn(response);
            AgentProcessRagEvent event = mock(AgentProcessRagEvent.class);
            lenient().when(event.getRagEvent()).thenReturn(ragResponseEvent);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.rag");
            assertNull(kv.get("embabel.rag.query"));
            assertEquals("vec-store", kv.get("embabel.rag.service"));
            assertEquals("0", kv.get("embabel.rag.result_count"));
        }

        @Test
        @DisplayName("planning omits world-state input and plan output but keeps the goal")
        void planningOmitsBodies() {
            listener().onProcessEvent(planEvent());

            Map<String, String> kv = kvOf("embabel.planning");
            assertNull(kv.get("input.value"));
            assertNull(kv.get("output.value"));
            assertEquals("done", kv.get("embabel.plan.goal"));
        }

        @Test
        @DisplayName("ready-to-plan omits the world-state input")
        void readyToPlanOmitsInput() {
            AgentProcess process = mock(AgentProcess.class);
            lenient().when(process.getId()).thenReturn("run-1");
            WorldState worldState = worldState("conditions: start");
            AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
            lenient().when(event.getWorldState()).thenReturn(worldState);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            assertNull(kvOf("embabel.planning.ready").get("input.value"));
        }

        @Test
        @DisplayName("replan omits the reason")
        void replanOmitsReason() {
            ReplanRequestedEvent event = mock(ReplanRequestedEvent.class);
            lenient().when(event.getReason()).thenReturn("stuck, retrying with new plan");

            listener().onProcessEvent(event);

            assertNull(kvOf("embabel.replan").get("embabel.replan.reason"));
        }

        @Test
        @DisplayName("goal achieved omits world-state input and result but keeps the goal name")
        void goalOmitsContent() {
            Goal goal = mock(Goal.class);
            lenient().when(goal.getName()).thenReturn("com.example.WizardAgent.answerQuestion");
            AgentProcess process = mock(AgentProcess.class);
            lenient().when(process.lastResult()).thenReturn("the answer");
            WorldState worldState = worldState("blackboard: UserInput, Answer");
            GoalAchievedEvent event = mock(GoalAchievedEvent.class);
            lenient().when(event.getGoal()).thenReturn(goal);
            lenient().when(event.getWorldState()).thenReturn(worldState);
            lenient().when(event.getAgentProcess()).thenReturn(process);

            listener().onProcessEvent(event);

            Map<String, String> kv = kvOf("embabel.goal");
            assertNull(kv.get("input.value"));
            assertNull(kv.get("embabel.goal.result"));
            assertEquals("com.example.WizardAgent.answerQuestion", kv.get("embabel.goal.name"));
        }
    }

    @Nested
    @DisplayName("registry gating")
    class Registry {

        @Test
        @DisplayName("NOOP registry produces no span and does not throw")
        void noopRegistry() {
            EmbabelSpanEventListener noop = new EmbabelSpanEventListener(ObservationRegistry.NOOP, properties);
            noop.onProcessEvent(llmEvent());
            assertTrue(handler.stopped.isEmpty());
        }

        @Test
        @DisplayName("NOOP registry produces no span on platform events either")
        void noopRegistryPlatformEvent() {
            EmbabelSpanEventListener noop = new EmbabelSpanEventListener(ObservationRegistry.NOOP, properties);
            noop.onPlatformEvent(dynamicAgentEvent());
            assertTrue(handler.stopped.isEmpty());
        }
    }

    @Nested
    @DisplayName("planIterations cleanup (no leak across runs)")
    class PlanIterationsCleanup {

        /** Iteration values (in order) carried by every emitted embabel.planning span. */
        private List<String> planningIterations() {
            return handler.stopped.stream()
                    .filter(c -> "embabel.planning".equals(c.getName()))
                    .map(c -> KeyValues.of(c.getLowCardinalityKeyValues())
                            .and(c.getHighCardinalityKeyValues())
                            .stream()
                            .filter(kv -> "embabel.plan.iteration".equals(kv.getKey()))
                            .map(KeyValue::getValue)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("planning span has no iteration attribute")))
                    .collect(Collectors.toList());
        }

        @Test
        @DisplayName("a completed event purges the counter, so a reused process id restarts planning at iteration 1")
        void completedEventResetsIterationCounter() {
            EmbabelSpanEventListener listener = listener();
            listener.onProcessEvent(planEvent()); // run-1 -> iteration 1
            listener.onProcessEvent(planEvent()); // run-1 -> iteration 2
            listener.onProcessEvent(completedEvent()); // purges run-1
            listener.onProcessEvent(planEvent()); // run-1 -> iteration 1 again iff purged (else 3)

            assertEquals(List.of("1", "2", "1"), planningIterations());
        }

        @Test
        @DisplayName("early termination also purges the counter (parallel to completed/failed/killed)")
        void earlyTerminationResetsIterationCounter() {
            EmbabelSpanEventListener listener = listener();
            listener.onProcessEvent(planEvent()); // run-1 -> iteration 1
            listener.onProcessEvent(new EarlyTermination(
                    processWithStatus("run-1", AgentProcessStatusCode.TERMINATED),
                    true, "budget exceeded", mock(EarlyTerminationPolicy.class)));
            listener.onProcessEvent(planEvent()); // run-1 -> iteration 1 again iff purged

            assertEquals(List.of("1", "1"), planningIterations());
        }
    }
}
