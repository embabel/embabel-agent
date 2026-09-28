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
package com.embabel.agent.config.models.lmstudio

import com.embabel.agent.openai.OpenAiClientTimeouts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class LmStudioClientTimeoutsTest {

    private fun bind(vararg properties: Pair<String, String>): LmStudioProperties =
        Binder(MapConfigurationPropertySource(properties.toMap()))
            .bindOrCreate(LmStudioProperties.PREFIX, LmStudioProperties::class.java)

    @Test
    fun `unset timeouts keep today's behaviour`() {
        assertEquals(OpenAiClientTimeouts.DEFAULT, bind().clientTimeouts())
    }

    @Test
    fun `timeouts bind under the lm studio prefix`() {
        val properties = bind(
            "embabel.agent.platform.models.lmstudio.connect-timeout" to "5s",
            "embabel.agent.platform.models.lmstudio.read-timeout" to "20m",
        )

        assertEquals(
            OpenAiClientTimeouts(connect = Duration.ofSeconds(5), read = Duration.ofMinutes(20)),
            properties.clientTimeouts(),
        )
    }
}
