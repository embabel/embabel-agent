/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.a2a.server.config

import com.embabel.agent.a2a.example.simple.horoscope.TestHoroscopeService
import com.embabel.agent.spi.Ranking
import com.embabel.agent.spi.Rankings
import com.embabel.agent.testing.integration.FakeRanker
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import org.junit.jupiter.api.Assertions.fail
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile


@Profile("test", "a2a")
@TestConfiguration
class FakeRankerConfiguration {

    private val logger = LoggerFactory.getLogger(FakeRankerConfiguration::class.java)

    init {
        logger.info("Using fake Ranker configuration for A2A.")
    }

    /**
     * Mock horoscope service for testing purposes.
     * Provides predictable horoscope responses without requiring external services.
     */
    @Bean
    @Primary
    fun fakeHoroscopeService() = TestHoroscopeService {
        """
            |On Monday, try to avoid being eaten by wolves            .
        """.trimMargin()
    }

    /**
     * Mock ranker implementation for testing purposes.
     * Provides deterministic ranking behavior for agents and goals:
     */
    @Bean
    @Primary
    fun fakeRanker() = object : FakeRanker {

        override fun <T> rank(
            description: String,
            userInput: String,
            rankables: Collection<T>,
        ): Rankings<T> where T : Named, T : Described {
            when (description) {
                "agent" -> {
                    val a = rankables.firstOrNull { it.name.contains("Star") } ?: fail { "No agent with Star found" }
                    return Rankings(
                        rankings = listOf(Ranking(a, 0.9))
                    )
                }

                "goal" -> {
                    val g =
                        rankables.firstOrNull { it.description.contains("horoscope") } ?: fail("No goal with horoscope")
                    return Rankings(
                        rankings = listOf(Ranking(g, 0.9))
                    )
                }

                else -> throw IllegalArgumentException("Unknown description $description")
            }
        }
    }
}
