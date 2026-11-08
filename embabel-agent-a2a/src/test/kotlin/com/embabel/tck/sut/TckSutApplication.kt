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
package com.embabel.tck.sut

import com.embabel.agent.AgentTestApplication
import com.embabel.agent.a2a.example.simple.horoscope.TestHoroscopeService
import com.embabel.agent.a2a.example.simple.horoscope.kotlin.TestStarNewsFinder
import com.embabel.agent.a2a.server.config.FakeAiConfiguration
import com.embabel.agent.a2a.server.config.FakeRankerConfiguration
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import org.springframework.beans.factory.annotation.Autowired

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener

@SpringBootApplication
@ComponentScan(basePackages = ["com.embabel.agent.a2a"])
@Import(
    AgentTestApplication::class,        // pulls in the platform component scans (api/core/spi/web/…)
    FakeAiConfiguration::class,
    FakeRankerConfiguration::class,
)
class TckSutApplication {
    @Autowired
    private lateinit var agentPlatform: AgentPlatform
    @Bean
    fun horoscopeService(): TestHoroscopeService = TestHoroscopeService { sign -> "Daily outlook for $sign: excellent" }

    @EventListener(ApplicationReadyEvent::class)
    fun deploy() {
        AgentMetadataReader().createAgentScopes(
            TestStarNewsFinder(horoscopeService(), wordCount = 100, storyCount = 5),
        ).forEach { agentPlatform.deploy(it) }
    }
}
fun main(args: Array<String>) {
    SpringApplication.run(TckSutApplication::class.java, *args)
}
