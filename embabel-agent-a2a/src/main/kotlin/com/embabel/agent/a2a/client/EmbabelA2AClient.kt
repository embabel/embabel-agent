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

import com.embabel.agent.a2a.A2AContextIdThreadLocalAccessor
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
            val client = cachedClient(baseUrl)
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
                                    // The SDK assembles the Task from the stream and applies each status update to it,
                                    // so event.task already carries the final status. It is never null: the
                                    // TaskUpdateEvent constructor rejects a null task.
                                    result.set(event.task)
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
            // Our own A2AException (e.g. the Message reply) already says what went wrong: rethrow it as is.
            error.get()?.let { throw it as? A2AException ?: A2AException("A2A sendMessage failed to $baseUrl", it) }
            val task = result.get()
                ?: throw A2AException("A2A remote agent at $baseUrl returned no Task")
            logger.debug("Received A2A response from {} taskId={} contextId={}", baseUrl, task.id, task.contextId)
            return task
        } catch (e: Exception) {
            observation.error(e)
            // The SDK throws its own exceptions (e.g. A2AClientException on an invalid JSON-RPC
            // response) straight out of client.sendMessage; callers get A2AException only.
            throw e as? A2AException ?: A2AException("A2A sendMessage failed to $baseUrl", e)
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
     * The same holds when the agent makes the call on another thread, in a parallel action
     * or `parallelMap`: [A2AContextIdThreadLocalAccessor] carries the contextId to that thread,
     * and [A2AContextIdThreadLocalAccessor.currentContextId] reads it there.
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
        A2AContextIdThreadLocalAccessor.currentContextId() ?: messageContextId

    override fun agentCard(baseUrl: String): AgentCard =
        cachedClient(baseUrl).getAgentCard(null)

    /**
     * The SDK client for [baseUrl], built on first use and then reused.
     *
     * [ConcurrentHashMap.computeIfAbsent] builds it at most once per agent: concurrent first calls
     * wait for the one card fetch instead of each fetching the card. (Kotlin's `getOrPut` would let
     * every concurrent caller run [buildClient].) A failed build caches nothing, so the next call retries.
     */
    private fun cachedClient(baseUrl: String): Client =
        clientCache.computeIfAbsent(baseUrl) { buildClient(it) }

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

    /**
     * Fetches and parses the agent card. Any failure (unreachable agent, HTTP error status,
     * empty body, invalid card JSON) surfaces as [A2AException] naming the card URL.
     */
    private fun fetchCard(baseUrl: String): AgentCard {
        val url = "$baseUrl/.well-known/agent.json"
        logger.debug("Fetching agent card from {}", url)
        val card = try {
            val json = cardRestClient.get().uri(url).retrieve().body(String::class.java)
                ?: throw A2AException("Empty response fetching agent card from $url")
            objectMapperHolder.get().readValue(json, AgentCard::class.java)
        } catch (e: Exception) {
            throw e as? A2AException ?: A2AException("Failed to fetch agent card from $url", e)
        }
        logger.debug(
            "Fetched agent card from {}: name='{}' version={} skills={}",
            url, card.name, card.version, card.skills?.map { it.id },
        )
        return card
    }
}
