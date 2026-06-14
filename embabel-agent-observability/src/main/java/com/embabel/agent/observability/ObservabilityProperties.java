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
package com.embabel.agent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Embabel Agent observability.
 *
 * <p>Works with any OpenTelemetry-compatible exporter (Zipkin, OTLP, Langfuse, etc.).
 *
 * @since 0.3.4
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.observability")
public class ObservabilityProperties {

    /**
     * Default constructor.
     */
    public ObservabilityProperties() {
    }

    /**
     * Grand master switch for all observability. When false, neither tracing nor metrics
     * auto-configuration is activated. Sits above {@link #tracingEnabled} and {@link #metricsEnabled},
     * which independently gate the two pillars when observability as a whole is enabled.
     */
    private boolean enabled = true;

    /**
     * Master switch for tracing (spans). When false, no spans are produced regardless of the
     * per-tier {@code trace-*} switches: the tracing handler, the span conventions and all span
     * enrichment are disabled in one shot. Independent of {@link #metricsEnabled} — aggregated
     * metrics keep flowing.
     */
    private boolean tracingEnabled = true;

    /** Master switch for Micrometer business metrics (counters, gauges). Independent of {@link #tracingEnabled}. */
    private boolean metricsEnabled = true;

    /** Service name for traces. */
    private String serviceName = "embabel-agent";

    /** Max attribute length before truncation. */
    private int maxAttributeLength = 4000;

    /**
     * Whether to capture LLM prompt/response message content on chat-model spans as the OTel GenAI
     * {@code gen_ai.input.messages} / {@code gen_ai.output.messages} attributes (and the OpenInference
     * {@code input.value} / {@code output.value} bridge). The OTel GenAI semantic convention recommends
     * content capture be opt-in because it may contain sensitive data (PII); this defaults to
     * {@code true} to preserve historical behaviour. Set to {@code false} to keep model/token metadata
     * while omitting message bodies.
     */
    private boolean captureMessageContent = true;

    /**
     * Umbrella switch for the core scoped span tier (agent, action, tool_loop, llm). When false, all
     * four scoped spans are suppressed at once and their conventions are not registered. For
     * per-span control while keeping the tier on, use {@link #traceAgent}, {@link #traceAction},
     * {@link #traceToolLoop} and {@link #traceLlmCalls}.
     */
    private boolean traceAgentEvents = true;

    /** Trace the {@code embabel.agent} scoped span (one run turn). Requires {@link #traceAgentEvents}. */
    private boolean traceAgent = true;

    /** Trace the {@code embabel.action} scoped span. Requires {@link #traceAgentEvents}. */
    private boolean traceAction = true;

    /** Trace tool calls. */
    private boolean traceToolCalls = true;

    /** Trace tool loop execution. */
    private boolean traceToolLoop = true;

    /** Trace LLM calls. */
    private boolean traceLlmCalls = true;

    /** Trace planning events. */
    private boolean tracePlanning = true;

    /** Trace state transitions. */
    private boolean traceStateTransitions = true;

    /** Trace lifecycle states. */
    private boolean traceLifecycleStates = true;

    /** Trace embedding invocations (model, token usage, cost). */
    private boolean traceEmbedding = true;

    /** Trace RAG responses (query, top-k, similarity threshold, result count, top score, RAGAS quality metrics). */
    private boolean traceRag = true;

    /** Trace ranking/selection events (agent routing decisions). */
    private boolean traceRanking = true;

    /** Trace dynamic agent creation events. */
    private boolean traceDynamicAgentCreation = true;

    /** Trace HTTP request/response details including bodies, headers and params (disabled by default; opt-in). */
    private boolean traceHttpDetails = false;

    /** Enable @Tracked annotation aspect for custom operation tracking. */
    private boolean traceTrackedOperations = true;

    /** Propagate Embabel context (run_id, agent name, action name) into SLF4J MDC for log correlation. */
    private boolean mdcPropagation = true;

    /**
     * Names of observations (spans) to suppress, matched by exact observation name. Lets you drop
     * non-Embabel infrastructure spans you don't want exported (Langfuse, Zipkin, …) without code,
     * e.g. {@code tasks.scheduled.execution} (@Scheduled tasks), {@code http.server.requests}
     * (incoming HTTP), {@code http.client.requests} (outgoing HTTP). Empty by default (nothing
     * suppressed). A suppressed observation becomes a no-op, so its children re-parent to the next
     * live ancestor.
     * <p>
     * Matches by name, so it works for any span that carries its real name at creation — including
     * Embabel point spans ({@code embabel.embedding}, {@code embabel.llm.invocation}, …). It does
     * <em>not</em> target the four core scoped spans ({@code embabel.agent}, {@code embabel.action},
     * {@code embabel.llm}, {@code embabel.tool_loop}): the core opens them with a placeholder name and
     * the semantic name is applied only later, so use their {@code trace-*} flags to toggle those.
     */
    private List<String> disabledTraces = new ArrayList<>();

    // Getters and Setters

    /**
     * Returns whether observability is enabled.
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether observability is enabled.
     * @param enabled true to enable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the service name for traces.
     * @return the service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Sets the service name for traces.
     * @param serviceName the service name
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Returns the max attribute length before truncation.
     * @return the max attribute length
     */
    public int getMaxAttributeLength() {
        return maxAttributeLength;
    }

    /**
     * Sets the max attribute length before truncation.
     * @param maxAttributeLength the max attribute length
     */
    public void setMaxAttributeLength(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    /**
     * Returns whether LLM message content is captured on chat-model spans.
     * @return {@code true} if prompt/response content is captured
     */
    public boolean isCaptureMessageContent() {
        return captureMessageContent;
    }

    /**
     * Sets whether LLM message content is captured on chat-model spans.
     * @param captureMessageContent {@code true} to capture prompt/response content
     */
    public void setCaptureMessageContent(boolean captureMessageContent) {
        this.captureMessageContent = captureMessageContent;
    }

    /**
     * Returns whether agent events tracing is enabled.
     * @return true if agent events are traced
     */
    public boolean isTraceAgentEvents() {
        return traceAgentEvents;
    }

    /**
     * Sets whether to trace agent events.
     * @param traceAgentEvents true to trace agent events
     */
    public void setTraceAgentEvents(boolean traceAgentEvents) {
        this.traceAgentEvents = traceAgentEvents;
    }

    /**
     * Returns whether the {@code embabel.agent} scoped span is traced.
     * @return true if the agent span is traced
     */
    public boolean isTraceAgent() {
        return traceAgent;
    }

    /**
     * Sets whether to trace the {@code embabel.agent} scoped span.
     * @param traceAgent true to trace the agent span
     */
    public void setTraceAgent(boolean traceAgent) {
        this.traceAgent = traceAgent;
    }

    /**
     * Returns whether the {@code embabel.action} scoped span is traced.
     * @return true if the action span is traced
     */
    public boolean isTraceAction() {
        return traceAction;
    }

    /**
     * Sets whether to trace the {@code embabel.action} scoped span.
     * @param traceAction true to trace the action span
     */
    public void setTraceAction(boolean traceAction) {
        this.traceAction = traceAction;
    }

    /**
     * Returns whether tool calls tracing is enabled.
     * @return true if tool calls are traced
     */
    public boolean isTraceToolCalls() {
        return traceToolCalls;
    }

    /**
     * Sets whether to trace tool calls.
     * @param traceToolCalls true to trace tool calls
     */
    public void setTraceToolCalls(boolean traceToolCalls) {
        this.traceToolCalls = traceToolCalls;
    }

    /**
     * Returns whether tool loop tracing is enabled.
     * @return true if tool loops are traced
     */
    public boolean isTraceToolLoop() {
        return traceToolLoop;
    }

    /**
     * Sets whether to trace tool loops.
     * @param traceToolLoop true to trace tool loops
     */
    public void setTraceToolLoop(boolean traceToolLoop) {
        this.traceToolLoop = traceToolLoop;
    }

    /**
     * Returns whether LLM calls tracing is enabled.
     * @return true if LLM calls are traced
     */
    public boolean isTraceLlmCalls() {
        return traceLlmCalls;
    }

    /**
     * Sets whether to trace LLM calls.
     * @param traceLlmCalls true to trace LLM calls
     */
    public void setTraceLlmCalls(boolean traceLlmCalls) {
        this.traceLlmCalls = traceLlmCalls;
    }

    /**
     * Returns whether planning events tracing is enabled.
     * @return true if planning events are traced
     */
    public boolean isTracePlanning() {
        return tracePlanning;
    }

    /**
     * Sets whether to trace planning events.
     * @param tracePlanning true to trace planning events
     */
    public void setTracePlanning(boolean tracePlanning) {
        this.tracePlanning = tracePlanning;
    }

    /**
     * Returns whether state transitions tracing is enabled.
     * @return true if state transitions are traced
     */
    public boolean isTraceStateTransitions() {
        return traceStateTransitions;
    }

    /**
     * Sets whether to trace state transitions.
     * @param traceStateTransitions true to trace state transitions
     */
    public void setTraceStateTransitions(boolean traceStateTransitions) {
        this.traceStateTransitions = traceStateTransitions;
    }

    /**
     * Returns whether lifecycle states tracing is enabled.
     * @return true if lifecycle states are traced
     */
    public boolean isTraceLifecycleStates() {
        return traceLifecycleStates;
    }

    /**
     * Sets whether to trace lifecycle states.
     * @param traceLifecycleStates true to trace lifecycle states
     */
    public void setTraceLifecycleStates(boolean traceLifecycleStates) {
        this.traceLifecycleStates = traceLifecycleStates;
    }

    /**
     * Returns whether embedding invocation tracing is enabled.
     * @return true if embedding invocations are traced
     */
    public boolean isTraceEmbedding() {
        return traceEmbedding;
    }

    /**
     * Sets whether to trace embedding invocations.
     * @param traceEmbedding true to trace embedding invocations
     */
    public void setTraceEmbedding(boolean traceEmbedding) {
        this.traceEmbedding = traceEmbedding;
    }

    /**
     * Returns whether RAG events tracing is enabled.
     * @return true if RAG events are traced
     */
    public boolean isTraceRag() {
        return traceRag;
    }

    /**
     * Sets whether to trace RAG events.
     * @param traceRag true to trace RAG events
     */
    public void setTraceRag(boolean traceRag) {
        this.traceRag = traceRag;
    }

    /**
     * Returns whether ranking events tracing is enabled.
     * @return true if ranking events are traced
     */
    public boolean isTraceRanking() {
        return traceRanking;
    }

    /**
     * Sets whether to trace ranking events.
     * @param traceRanking true to trace ranking events
     */
    public void setTraceRanking(boolean traceRanking) {
        this.traceRanking = traceRanking;
    }

    /**
     * Returns whether dynamic agent creation tracing is enabled.
     * @return true if dynamic agent creation is traced
     */
    public boolean isTraceDynamicAgentCreation() {
        return traceDynamicAgentCreation;
    }

    /**
     * Sets whether to trace dynamic agent creation.
     * @param traceDynamicAgentCreation true to trace dynamic agent creation
     */
    public void setTraceDynamicAgentCreation(boolean traceDynamicAgentCreation) {
        this.traceDynamicAgentCreation = traceDynamicAgentCreation;
    }

    /**
     * Returns whether HTTP request/response details tracing is enabled.
     * @return true if HTTP details are traced
     */
    public boolean isTraceHttpDetails() {
        return traceHttpDetails;
    }

    /**
     * Sets whether to trace HTTP request/response details.
     * @param traceHttpDetails true to trace HTTP details
     */
    public void setTraceHttpDetails(boolean traceHttpDetails) {
        this.traceHttpDetails = traceHttpDetails;
    }

    /**
     * Returns whether @Tracked annotation tracing is enabled.
     * @return true if @Tracked operations are traced
     */
    public boolean isTraceTrackedOperations() {
        return traceTrackedOperations;
    }

    /**
     * Sets whether to trace @Tracked annotated operations.
     * @param traceTrackedOperations true to enable @Tracked aspect
     */
    public void setTraceTrackedOperations(boolean traceTrackedOperations) {
        this.traceTrackedOperations = traceTrackedOperations;
    }

    /**
     * Returns whether MDC propagation is enabled.
     * @return true if MDC propagation is enabled
     */
    public boolean isMdcPropagation() {
        return mdcPropagation;
    }

    /**
     * Sets whether to propagate Embabel context into SLF4J MDC.
     * @param mdcPropagation true to enable MDC propagation
     */
    public void setMdcPropagation(boolean mdcPropagation) {
        this.mdcPropagation = mdcPropagation;
    }

    /**
     * Returns whether Micrometer business metrics are enabled.
     * @return true if metrics are enabled
     */
    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    /**
     * Sets whether to enable Micrometer business metrics.
     * @param metricsEnabled true to enable metrics
     */
    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    /**
     * Returns whether tracing (spans) is enabled. Master switch over all {@code trace-*} switches.
     * @return true if tracing is enabled
     */
    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    /**
     * Sets whether tracing (spans) is enabled. When false, all span production is disabled
     * regardless of the per-tier {@code trace-*} switches.
     * @param tracingEnabled true to enable tracing
     */
    public void setTracingEnabled(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }

    /**
     * Returns the names of observations to suppress.
     * @return the list of disabled observation names (never null; empty means none)
     */
    public List<String> getDisabledTraces() {
        return disabledTraces;
    }

    /**
     * Sets the names of observations to suppress (matched by exact observation name).
     * @param disabledTraces the observation names to drop
     */
    public void setDisabledTraces(List<String> disabledTraces) {
        this.disabledTraces = disabledTraces == null ? new ArrayList<>() : disabledTraces;
    }
}
