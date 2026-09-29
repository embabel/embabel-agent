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
package com.embabel.agent.spi.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class ClientTimeoutPropertiesTest {

    private class ProviderProperties : ClientTimeoutProperties()

    private fun bind(vararg properties: Pair<String, String>): ProviderProperties =
        Binder(MapConfigurationPropertySource(properties.toMap()))
            .bindOrCreate("embabel.agent.platform.models.test", ProviderProperties::class.java)

    @Test
    fun `unset timeouts are null, so the provider decides the fallback`() {
        val properties = bind()

        assertNull(properties.connectTimeout)
        assertNull(properties.readTimeout)
    }

    @Test
    fun `timeouts bind under the provider's prefix`() {
        val properties = bind(
            "embabel.agent.platform.models.test.connect-timeout" to "5s",
            "embabel.agent.platform.models.test.read-timeout" to "10m",
        )

        assertEquals(Duration.ofSeconds(5), properties.connectTimeout)
        assertEquals(Duration.ofMinutes(10), properties.readTimeout)
    }
}
