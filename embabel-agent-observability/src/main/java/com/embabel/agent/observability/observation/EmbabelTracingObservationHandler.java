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

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom TracingObservationHandler for Embabel observations.
 * Creates root spans for agents and manages parent-child hierarchy for actions/tools.
 *
 * @author Quantpulsar 2025-2026
 */
public class EmbabelTracingObservationHandler
        implements TracingObservationHandler<EmbabelObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(EmbabelTracingObservationHandler.class);

    private final Tracer tracer;

    private final Map<String, Span> activeAgentSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> activeActionSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> activeLlmSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> activeToolLoopSpans = new ConcurrentHashMap<>();
    private final Map<Integer, Tracer.SpanInScope> activeScopes = new ConcurrentHashMap<>();

    /**
     * Creates a new handler.
     *
     * @param tracer     the Micrometer tracer
     */
    public EmbabelTracingObservationHandler(Tracer tracer) {
        this.tracer = tracer;
        log.info("EmbabelTracingObservationHandler initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Tracer getTracer() {
        return tracer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof EmbabelObservationContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStart(EmbabelObservationContext context) {
        Span span;

        if (context.isRoot()) {
            // Check if there's an active span (e.g., from HTTP request or parent agent)
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                // Attach agent to existing trace (HTTP request, parent agent, etc.)
                span = tracer.nextSpan(currentSpan).name(context.getName());
                span.start();
                log.debug("Created agent span as child of existing trace for: {} (runId: {})",
                        context.getName(), context.getRunId());
            } else {
                // No active trace - create root span (new trace)
                span = createRootSpan(context.getName());
                log.debug("Created root span for agent: {} (runId: {})", context.getName(), context.getRunId());
            }
        } else {
            // Child span with parent resolution
            Span parentSpan = resolveParentSpan(context);
            if (parentSpan != null) {
                span = tracer.nextSpan(parentSpan).name(context.getName());
            } else {
                log.warn("No parent span found for {} '{}' (runId: {}), span will use thread-local context. " +
                                "Active maps: agents={}, actions={}, llms={}, toolLoops={}",
                        context.getEventType(), context.getName(), context.getRunId(),
                        activeAgentSpans.keySet(), activeActionSpans.keySet(),
                        activeLlmSpans.keySet(), activeToolLoopSpans.keySet());
                span = tracer.nextSpan().name(context.getName());
            }
            span.start();
            log.debug("Created child span for {}: {} (runId: {})",
                    context.getEventType(), context.getName(), context.getRunId());
        }

        span.tag("embabel.event.type", context.getEventType().name().toLowerCase());
        span.tag("embabel.run_id", context.getRunId());
        getTracingContext(context).setSpan(span);
        trackActiveSpan(context, span);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onScopeOpened(EmbabelObservationContext context) {
        Span span = getTracingContext(context).getSpan();
        if (span != null) {
            // Put span in thread-local for Spring AI integration
            Tracer.SpanInScope scope = tracer.withSpan(span);
            activeScopes.put(System.identityHashCode(context), scope);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onScopeClosed(EmbabelObservationContext context) {
        Tracer.SpanInScope scope = activeScopes.remove(System.identityHashCode(context));
        if (scope != null) {
            scope.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStop(EmbabelObservationContext context) {
        Span span = getRequiredSpan(context);

        for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
            span.tag(keyValue.getKey(), keyValue.getValue());
        }
        for (KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            span.tag(keyValue.getKey(), keyValue.getValue());
        }
        if (context.getContextualName() != null) {
            span.name(context.getContextualName());
        }

        // Propagate error from observation context to span
        Throwable error = context.getError();
        if (error != null) {
            span.error(error);
        }

        untrackActiveSpan(context);
        span.end();
        log.debug("Ended span for {}: {} (runId: {})",
                context.getEventType(), context.getName(), context.getRunId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(EmbabelObservationContext context) {
        Throwable error = context.getError();
        if (error != null) {
            Span span = getTracingContext(context).getSpan();
            if (span != null) {
                span.error(error);
            }
        }
    }

    /**
     * Creates a root span with no parent by clearing context.
     *
     * @param name the span name
     * @return the created root span
     */
    private Span createRootSpan(String name) {
        Span rootSpan;
        try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
            rootSpan = tracer.nextSpan().name(name);
            rootSpan.start();
        }
        return rootSpan;
    }

    /**
     * Resolves the parent span from Embabel hierarchy or current tracer context.
     * For tool calls, uses current tracer span to integrate with Spring AI ChatClient.
     *
     * @param context the observation context
     * @return the parent span, or null if none
     */
    private Span resolveParentSpan(EmbabelObservationContext context) {
        // Check if the listener has explicitly set a parent observation.
        // This handles cross-thread scenarios (e.g., CompletableFuture.supplyAsync)
        // where tracer.currentSpan() returns the wrong span.
        var parentObs = context.getParentObservation();
        if (parentObs != null && parentObs.getContextView() instanceof Observation.Context parentCtx) {
            TracingContext parentTracingCtx = parentCtx.get(TracingContext.class);
            if (parentTracingCtx != null && parentTracingCtx.getSpan() != null) {
                log.debug("Parent span resolved from parentObservation for {} '{}' (runId: {})",
                        context.getEventType(), context.getName(), context.getRunId());
                return parentTracingCtx.getSpan();
            }
        }

        String runId = context.getRunId();

        switch (context.getEventType()) {
            case ACTION:
                return activeAgentSpans.get(runId);

            case AGENT_PROCESS:
                if (context.getParentRunId() != null) {
                    // Prefer parent's active action span (sub-agent runs inside an action)
                    Span parentActionSpan = activeActionSpans.get(context.getParentRunId());
                    if (parentActionSpan != null) {
                        return parentActionSpan;
                    }
                    return activeAgentSpans.get(context.getParentRunId());
                }
                return null;

            case LLM_CALL:
                Span llmActionSpan = activeActionSpans.get(runId);
                if (llmActionSpan != null) {
                    return llmActionSpan;
                }
                return activeAgentSpans.get(runId);

            case TOOL_LOOP:
                // Prefer tracer.currentSpan() — the LLM span has already called
                // tracer.withSpan() on this worker thread, so currentSpan() returns
                // the correct parent. This supports parallel LLM calls.
                Span currentToolLoopSpan = tracer.currentSpan();
                if (currentToolLoopSpan != null) {
                    log.debug("TOOL_LOOP parent resolved to tracer.currentSpan() (runId: {})", runId);
                    return currentToolLoopSpan;
                }
                Span toolLoopActionSpan = activeActionSpans.get(runId);
                if (toolLoopActionSpan != null) {
                    log.debug("TOOL_LOOP parent resolved to ACTION span (runId: {}, no currentSpan)", runId);
                    return toolLoopActionSpan;
                }
                log.debug("TOOL_LOOP parent resolved to AGENT span (runId: {}, no currentSpan or ACTION)", runId);
                return activeAgentSpans.get(runId);

            case TOOL_CALL:
                // For tool calls, prefer current tracer span (could be Spring AI ChatClient)
                // This ensures tools are nested under LLM calls
                Span currentSpan = tracer.currentSpan();
                if (currentSpan != null) {
                    return currentSpan;
                }
                // Fallback to Embabel hierarchy
                Span toolActionSpan = activeActionSpans.get(runId);
                if (toolActionSpan != null) {
                    return toolActionSpan;
                }
                return activeAgentSpans.get(runId);

            case GOAL:
            case PLANNING:
            case STATE_TRANSITION:
            case LIFECYCLE:
            case CUSTOM:
                Span actionSpan = activeActionSpans.get(runId);
                if (actionSpan != null) {
                    return actionSpan;
                }
                return activeAgentSpans.get(runId);

            default:
                return null;
        }
    }

    /**
     * Tracks active span for parent resolution.
     *
     * @param context the observation context
     * @param span    the span to track
     */
    private void trackActiveSpan(EmbabelObservationContext context, Span span) {
        switch (context.getEventType()) {
            case AGENT_PROCESS:
                activeAgentSpans.put(context.getRunId(), span);
                break;
            case ACTION:
                activeActionSpans.put(context.getRunId(), span);
                break;
            case LLM_CALL:
                // Use identityHashCode to support parallel LLM calls with same runId
                activeLlmSpans.put(String.valueOf(System.identityHashCode(context)), span);
                break;
            case TOOL_LOOP:
                // Use identityHashCode to support parallel tool loops with same runId
                activeToolLoopSpans.put(String.valueOf(System.identityHashCode(context)), span);
                break;
            default:
                break;
        }
    }

    /**
     * Removes span from active tracking when observation stops.
     *
     * @param context the observation context
     */
    private void untrackActiveSpan(EmbabelObservationContext context) {
        switch (context.getEventType()) {
            case AGENT_PROCESS:
                activeAgentSpans.remove(context.getRunId());
                break;
            case ACTION:
                activeActionSpans.remove(context.getRunId());
                break;
            case LLM_CALL:
                activeLlmSpans.remove(String.valueOf(System.identityHashCode(context)));
                break;
            case TOOL_LOOP:
                activeToolLoopSpans.remove(String.valueOf(System.identityHashCode(context)));
                break;
            default:
                break;
        }
    }
}
