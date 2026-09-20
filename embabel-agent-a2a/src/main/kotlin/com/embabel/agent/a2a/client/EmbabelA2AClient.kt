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

import com.embabel.agent.a2a.A2A_CONTEXT_ID_BAGGAGE_KEY
import com.embabel.agent.a2a.client.api.A2AClient
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.client.Client
import io.a2a.client.ClientEvent
import io.a2a.client.TaskEvent
import io.a2a.client.TaskUpdateEvent
import io.a2a.client.transport.jsonrpc.JSONRPCTransport
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder
import io.a2a.spec.AgentCard
import io.a2a.spec.Message
import io.a2a.spec.Task
import io.a2a.spec.TaskStatusUpdateEvent
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.baggage.BaggageEntryMetadata
import io.opentelemetry.context.Context
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.util.function.BiConsumer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EmbabelA2AClient @JvmOverloads constructor(
    private val httpClientFactory: A2AHttpClientFactory,
    private val objectMapperHolder: EmbabelObjectMapperHolder,
    private val timeoutSeconds: Long = 30,
) : A2AClient {

    private val logger = LoggerFactory.getLogger(EmbabelA2AClient::class.java)
    private val clientCache = ConcurrentHashMap<String, Client>()

    override fun sendMessage(baseUrl: String, message: Message): Task {
        val contextId = resolveContextId(message.contextId)
        logger.debug("Sending A2A message to {} contextId={}", baseUrl, contextId)
        val client = clientCache.computeIfAbsent(baseUrl) { buildClient(it) }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Task>()
        val error = AtomicReference<Throwable>()

        // When contextId was resolved from OTel baggage, inject it into the message body so the
        // receiving server sees it without requiring OTel-instrumented HTTP transport.
        val outboundMessage = if (contextId != null && contextId != message.contextId) {
            var b = Message.Builder()
                .messageId(message.messageId)
                .role(message.role)
                .parts(message.parts)
                .contextId(contextId)
            if (message.taskId != null) b = b.taskId(message.taskId)
            b.build()
        } else {
            message
        }

        val baggageScope = openBaggageScope(contextId)
        try {
            client.sendMessage(
                outboundMessage,
                listOf(BiConsumer { event: ClientEvent, _: AgentCard ->
                    when (event) {
                        is TaskEvent -> {
                            // non-streaming path: server returned a Task directly
                            result.set(event.task)
                            latch.countDown()
                        }
                        is TaskUpdateEvent -> {
                            // streaming path: server sends TaskStatusUpdateEvent via SSE;
                            // release latch only on the final event
                            val update = event.updateEvent
                            if (update is TaskStatusUpdateEvent && update.isFinal) {
                                val task = event.task ?: Task.Builder()
                                    .id(update.taskId)
                                    .contextId(update.contextId)
                                    .status(update.status)
                                    .build()
                                result.set(task)
                                latch.countDown()
                            }
                        }
                        else -> {}
                    }
                }),
                { t -> error.set(t); latch.countDown() },
                null,
            )

            check(latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "A2A sendMessage to $baseUrl timed out after ${timeoutSeconds}s"
            }
        } finally {
            baggageScope?.close()
        }
        error.get()?.let { throw RuntimeException("A2A sendMessage failed to $baseUrl", it) }
        return result.get().also {
            logger.debug("Received A2A response from {} taskId={} contextId={}", baseUrl, it.id, it.contextId)
        }
    }

    /**
     * Resolves the effective contextId for an outbound A2A call.
     * Prefers an inherited contextId from OTel baggage (set by an upstream A2A server handler)
     * over the message's own contextId. This preserves the original session ID across a chain:
     * Orchestrator → AgentOne → AgentFour all share the Orchestrator's contextId.
     */
    internal fun resolveContextId(messageContextId: String?): String? =
        if (OTEL_AVAILABLE) Baggage.current().getEntryValue(BAGGAGE_KEY) ?: messageContextId
        else messageContextId

    private fun openBaggageScope(contextId: String?): AutoCloseable? {
        if (!OTEL_AVAILABLE || contextId == null) return null
        return Baggage.current().toBuilder()
            .put(BAGGAGE_KEY, contextId, BaggageEntryMetadata.empty())
            .build()
            .storeInContext(Context.current())
            .makeCurrent()
    }

    companion object {
        internal const val BAGGAGE_KEY = A2A_CONTEXT_ID_BAGGAGE_KEY
        internal val OTEL_AVAILABLE = try {
            Class.forName("io.opentelemetry.api.baggage.Baggage")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    override fun agentCard(baseUrl: String): AgentCard =
        clientCache.getOrPut(baseUrl) { buildClient(baseUrl) }.getAgentCard(null)

    private fun buildClient(baseUrl: String): Client {
        logger.debug("Building A2A client for {} (not cached)", baseUrl)
        val card = fetchCard(baseUrl)
        return Client.builder(card)
            .withTransport(
                JSONRPCTransport::class.java,
                JSONRPCTransportConfigBuilder().httpClient(httpClientFactory.create()).build(),
            )
            .build()
    }

    private fun fetchCard(baseUrl: String): AgentCard {
        val url = "$baseUrl/.well-known/agent.json"
        logger.debug("Fetching agent card from {}", url)
        val json = RestClient.create().get().uri(url).retrieve().body(String::class.java)
            ?: error("Empty response fetching agent card from $url")
        val card = objectMapperHolder.get().readValue(json, AgentCard::class.java)
        logger.debug(
            "Fetched agent card from {}: name='{}' version={} skills={}",
            url, card.name, card.version, card.skills?.map { it.id },
        )
        return card
    }
}
