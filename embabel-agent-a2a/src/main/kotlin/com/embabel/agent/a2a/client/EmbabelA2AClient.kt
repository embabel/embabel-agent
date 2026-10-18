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
package com.embabel.agent.a2a.client

import com.embabel.agent.a2a.A2A_CLIENT_SEND
import com.embabel.agent.a2a.A2A_CONTEXT_ID_KEY
import com.embabel.agent.a2a.A2A_CONTEXT_ID_SPAN_KEY
import com.embabel.agent.a2a.A2A_TASK_ID_KEY
import com.embabel.agent.a2a.client.api.A2AClient
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.client.Client
import io.a2a.client.ClientEvent
import io.a2a.client.MessageEvent
import io.a2a.client.TaskEvent
import io.a2a.client.TaskUpdateEvent
import io.a2a.client.config.ClientConfig
import io.a2a.client.transport.jsonrpc.JSONRPCTransport
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder
import io.a2a.spec.AgentCard
import io.a2a.spec.Message
import io.a2a.spec.Task
import io.a2a.spec.TaskStatusUpdateEvent
import io.opentelemetry.context.Context
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer

class EmbabelA2AClient @JvmOverloads constructor(
    private val httpClientFactory: A2AHttpClientFactory,
    private val objectMapperHolder: EmbabelObjectMapperHolder,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val cardRestClient: RestClient = RestClient.create(),
) : A2AClient {

    private val logger = LoggerFactory.getLogger(EmbabelA2AClient::class.java)
    private val clientCache = ConcurrentHashMap<String, Client>()

    override fun sendMessage(baseUrl: String, message: Message): Task {
        val contextId = resolveContextId(message.contextId)
        logger.debug("Sending A2A message to {} contextId={}", baseUrl, contextId)

        // contextId and taskId are per-request UUIDs (unbounded) — high-cardinality keys are
        // exported to traces/spans only, never to metrics, avoiding cardinality explosion.
        val observation = Observation.createNotStarted(A2A_CLIENT_SEND, observationRegistry)
            .highCardinalityKeyValue(A2A_CONTEXT_ID_SPAN_KEY, contextId ?: "")
            .highCardinalityKeyValue(A2A_TASK_ID_KEY, message.taskId ?: "") // NOSONAR: taskId is nullable; empty string is the correct fallback for the span tag
            .start()
        // openScope() makes this observation the current one on the thread, so the outgoing
        // HTTP call registers as a child span of a2a.client.send in the trace hierarchy.
        val observationScope = observation.openScope()
        try {
            val client = clientCache.computeIfAbsent(baseUrl) { buildClient(it) }
            // client.sendMessage with setStreaming(false) is synchronous: the SDK fires consumers
            // and returns before this call unwinds, so no latch is needed.
            val result = AtomicReference<Task>()
            val error = AtomicReference<Throwable>()

            // Propagate the session contextId across the hop: if resolveContextId returned a value
            // from the OTel context key (inherited from an upstream A2A request), inject it into
            // the outbound message body so the next agent in the chain receives the same session ID.
            val outboundMessage = if (contextId != null && contextId != message.contextId) {
                Message.Builder(message).contextId(contextId).build()
            } else {
                message
            }

            client.sendMessage(
                    outboundMessage,
                    listOf(BiConsumer { event: ClientEvent, _: AgentCard ->
                        when (event) {
                            is TaskEvent -> {
                                // non-streaming path: server returned a Task directly
                                result.set(event.task)
                            }
                            is TaskUpdateEvent -> {
                                // streaming path: the SDK sends incremental status events via SSE.
                                // only the final event carries the completed task — ignore intermediates.
                                val update = event.updateEvent
                                if (update is TaskStatusUpdateEvent && update.isFinal) {
                                    // event.task is set by the SDK when it can assemble the full Task from
                                    // the stream; fall back to building from the status event fields when it cannot.
                                    result.set(event.task ?: Task.Builder()
                                        .id(update.taskId)
                                        .contextId(update.contextId)
                                        .status(update.status)
                                        .build())
                                }
                            }
                            is MessageEvent -> {
                                // A2A allows message/send to return a Message instead of a Task.
                                // Embabel's sendMessage contract returns Task; provide a clear error
                                // rather than silently timing out waiting for a Task that will never arrive.
                                error.set(A2AException(
                                    "A2A remote agent at $baseUrl returned a Message reply instead of a Task — " +
                                    "multi-turn Message responses are not yet supported"
                                ))
                            }
                            else -> {}
                        }
                    }),
                    { t -> error.set(t) },
                    null,
                )
            error.get()?.let { throw A2AException("A2A sendMessage failed to $baseUrl", it) }
            return result.get().also {
                logger.debug("Received A2A response from {} taskId={} contextId={}", baseUrl, it.id, it.contextId)
            }
        } catch (e: Exception) {
            observation.error(e)
            throw e
        } finally {
            observationScope.close()
            observation.stop()
        }
    }

    /**
     * Returns the contextId to use on the outbound A2A message.
     *
     * When this client runs inside an agent that was itself called over A2A,
     * [AutonomyA2ARequestHandler] stores the inbound contextId in [A2A_CONTEXT_ID_KEY]
     * before executing the agent. Reading that key here means the outbound message
     * automatically carries the same session identifier as the inbound one — the chain
     * is preserved without agent business code having to pass contextId explicitly.
     *
     * Falls back to the message's own contextId when no key is set (e.g. the call
     * originates outside an A2A server request).
     *
     * [A2A_CONTEXT_ID_KEY] is a JVM-local OTel [io.opentelemetry.context.ContextKey]: it is
     * never serialised into HTTP headers, so it cannot reach LLM providers or other
     * external services. Contrast with OTel baggage, which is injected as a `baggage:`
     * HTTP header on every instrumented outbound call.
     */
    internal fun resolveContextId(messageContextId: String?): String? =
        Context.current().get(A2A_CONTEXT_ID_KEY) ?: messageContextId

    override fun agentCard(baseUrl: String): AgentCard =
        clientCache.getOrPut(baseUrl) { buildClient(baseUrl) }.getAgentCard(null)

    private fun buildClient(baseUrl: String): Client {
        logger.debug("Building A2A client for {} (not cached)", baseUrl)
        val card = fetchCard(baseUrl)
        return Client.builder(card)
            .clientConfig(ClientConfig.Builder().setStreaming(false).build())
            .withTransport(
                JSONRPCTransport::class.java,
                JSONRPCTransportConfigBuilder().httpClient(httpClientFactory.create()).build(),
            )
            .build()
    }

    private fun fetchCard(baseUrl: String): AgentCard {
        val url = "$baseUrl/.well-known/agent.json"
        logger.debug("Fetching agent card from {}", url)
        val json = cardRestClient.get().uri(url).retrieve().body(String::class.java)
            ?: error("Empty response fetching agent card from $url")
        val card = objectMapperHolder.get().readValue(json, AgentCard::class.java)
        logger.debug(
            "Fetched agent card from {}: name='{}' version={} skills={}",
            url, card.name, card.version, card.skills?.map { it.id },
        )
        return card
    }
}
