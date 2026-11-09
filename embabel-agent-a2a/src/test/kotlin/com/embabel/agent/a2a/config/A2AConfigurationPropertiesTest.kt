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
package com.embabel.agent.a2a.config

import com.embabel.agent.a2a.server.support.TaskStateManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class A2AConfigurationPropertiesTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration::class.java)
        .withBean(TaskStateManager::class.java)

    @Nested
    inner class TaskRetention {

        @Test
        fun `defaults to one hour`() {
            contextRunner.run { context ->
                assertEquals(Duration.ofHours(1), context.getBean(TaskStateManager::class.java).taskRetention)
            }
        }

        @Test
        fun `is bound from the server task retention property`() {
            contextRunner
                .withPropertyValues("embabel.agent.platform.a2a.server.task-retention=15m")
                .run { context ->
                    assertEquals(Duration.ofMinutes(15), context.getBean(TaskStateManager::class.java).taskRetention)
                }
        }
    }

    @EnableConfigurationProperties(A2AConfigurationProperties::class)
    class PropertiesConfiguration
}
