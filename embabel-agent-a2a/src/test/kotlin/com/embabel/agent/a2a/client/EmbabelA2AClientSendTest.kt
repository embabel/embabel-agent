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

import com.embabel.agent.a2a.client.spi.SpringRestClientA2AHttpClient
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.A2AClientException
import io.a2a.spec.Message
import io.a2a.spec.TextPart
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * How [EmbabelA2AClient.sendMessage] reports a remote agent's reply that it cannot turn into a Task.
 * Runs over real HTTP against an [A2ATestAgent].
 */
class EmbabelA2AClientSendTest {

    private var agent: A2ATestAgent? = null

    @AfterEach
    fun stopAgent() {
        agent?.close()
    }

    private fun clientFor(remote: A2ATestAgent): EmbabelA2AClient {
        agent = remote
        val restClient = RestClient.create()
        return EmbabelA2AClient(
            httpClientFactory = { SpringRestClientA2AHttpClient(restClient, ExecutorAsyncer { it.run() }) },
            objectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            cardRestClient = restClient,
        )
    }

    private fun message(): Message =
        Message.Builder()
            .role(Message.Role.USER)
            .messageId(UUID.randomUUID().toString())
            .parts(listOf(TextPart("hello")))
            .build()

    /**
     * The SDK rejects `"result": null` as an invalid JSON-RPC response and throws its own
     * [A2AClientException] from `Client.sendMessage`. Callers must see Embabel's [A2AException]
     * naming the agent, with the SDK failure kept as the cause.
     */
    @Test
    fun `SDK failure surfaces as A2AException with the SDK cause`() {
        val remote = A2ATestAgent { id -> A2ATestAgent.jsonRpcResult(id, "null") }
        val client = clientFor(remote)

        val e = assertThrows<A2AException> { client.sendMessage(remote.baseUrl, message()) }

        assertTrue(remote.baseUrl in e.message.orEmpty(), "must name the remote agent: ${e.message}")
        assertInstanceOf(A2AClientException::class.java, e.cause)
    }

    /**
     * A Message reply is reported by our own [A2AException], which must reach the caller as is,
     * not wrapped in a second, generic one.
     */
    @Test
    fun `Message reply is reported once, not wrapped twice`() {
        val reply = """{"kind":"message","role":"agent","messageId":"m-1","parts":[{"kind":"text","text":"hi"}]}"""
        val remote = A2ATestAgent { id -> A2ATestAgent.jsonRpcResult(id, reply) }
        val client = clientFor(remote)

        val e = assertThrows<A2AException> { client.sendMessage(remote.baseUrl, message()) }

        assertTrue("Message reply" in e.message.orEmpty(), "must say the agent sent a Message: ${e.message}")
        assertNull(e.cause, "must not be wrapped: cause was ${e.cause}")
    }
}
