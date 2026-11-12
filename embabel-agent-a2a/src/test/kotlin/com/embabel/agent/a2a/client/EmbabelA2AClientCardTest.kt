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

import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.AgentCard
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Agent-card discovery in [EmbabelA2AClient], against an [A2ATestAgent].
 */
class EmbabelA2AClientCardTest {

    // Slow enough that concurrent callers overlap while the first fetch is in flight
    private val agent = A2ATestAgent(cardDelayMillis = 300)

    @AfterEach
    fun stopAgent() {
        agent.close()
    }

    @Test
    fun `concurrent agentCard calls for the same agent fetch the card once`() {
        val client = EmbabelA2AClient(mockk(relaxed = true), EmbabelObjectMapperHolder.createDefault())
        val start = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(THREADS)
        try {
            val futures = (1..THREADS).map {
                callers.submit<AgentCard> {
                    start.await()
                    client.agentCard(agent.baseUrl)
                }
            }
            start.countDown()

            val cards = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, agent.cardRequests.get(), "the card must be fetched once and then served from the cache")
            assertEquals(setOf(A2ATestAgent.CARD_NAME), cards.map { it.name }.toSet())
        } finally {
            callers.shutdownNow()
        }
    }

    /**
     * A card that cannot be fetched (here: nothing listening) must surface as Embabel's [A2AException]
     * naming the card URL, not as a raw Spring [org.springframework.web.client.RestClientException].
     */
    @Test
    fun `unreachable agent surfaces as A2AException naming the card URL`() {
        val unreachable = A2ATestAgent().use { it.baseUrl } // port is free once the agent stops
        val client = EmbabelA2AClient(mockk(relaxed = true), EmbabelObjectMapperHolder.createDefault())

        val e = assertThrows<A2AException> { client.agentCard(unreachable) }

        assertTrue("$unreachable/.well-known/agent.json" in e.message.orEmpty(), "must name the card URL: ${e.message}")
        assertNotNull(e.cause, "must keep the underlying failure")
    }

    private companion object {
        const val THREADS = 8
    }
}
