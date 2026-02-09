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

/**
 * Configuration properties for Embabel Agent observability.
 *
 * <p>Works with any OpenTelemetry-compatible exporter (Zipkin, OTLP, Langfuse, etc.).
 *
 * @since 0.3.4
 */
@ConfigurationProperties(prefix = "embabel.observability")
public class ObservabilityProperties {

    /**
     * Default constructor.
     */
    public ObservabilityProperties() {
    }

    /** Enable/disable observability. */
    private boolean enabled = true;

    /** Service name for traces. */
    private String serviceName = "embabel-agent";

    /** Tracer instrumentation name. */
    private String tracerName = "embabel-agent";

    /** Tracer version. */
    private String tracerVersion = "0.3.4";

    /** Max attribute length before truncation. */
    private int maxAttributeLength = 4000;

    /** Trace agent events (agents, actions, goals). */
    private boolean traceAgentEvents = true;

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

    /** Trace RAG events (request, response, pipeline). */
    private boolean traceRag = true;

    /** Trace ranking/selection events (agent routing decisions). */
    private boolean traceRanking = true;

    /** Trace dynamic agent creation events. */
    private boolean traceDynamicAgentCreation = true;

    /** Trace HTTP request/response details including bodies, headers and params (enabled by default). */
    private boolean traceHttpDetails = true;

    /** Enable @Tracked annotation aspect for custom operation tracking. */
    private boolean traceTrackedOperations = true;

    /** Propagate Embabel context (run_id, agent name, action name) into SLF4J MDC for log correlation. */
    private boolean mdcPropagation = true;

    /** Enable/disable Micrometer business metrics (counters, gauges). */
    private boolean metricsEnabled = true;

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
     * Returns the tracer instrumentation name.
     * @return the tracer name
     */
    public String getTracerName() {
        return tracerName;
    }

    /**
     * Sets the tracer instrumentation name.
     * @param tracerName the tracer name
     */
    public void setTracerName(String tracerName) {
        this.tracerName = tracerName;
    }

    /**
     * Returns the tracer version.
     * @return the tracer version
     */
    public String getTracerVersion() {
        return tracerVersion;
    }

    /**
     * Sets the tracer version.
     * @param tracerVersion the tracer version
     */
    public void setTracerVersion(String tracerVersion) {
        this.tracerVersion = tracerVersion;
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
}
