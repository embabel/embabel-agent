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
package com.embabel.agent.openai

import com.embabel.common.util.ObjectProviders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import org.springframework.beans.factory.ObjectProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class OpenAiCompatibleModelFactoryHttpClientCustomizerTest {

    @Test
    fun `no customizers - factory constructs without error`() {
        OpenAiCompatibleModelFactory(
            baseUrl = null,
            apiKey = null,
            httpClientCustomizers = ObjectProviders.empty(),
        )
    }

    @Test
    fun `single customizer is invoked during construction`() {
        val invoked = AtomicBoolean(false)
        val customizer = OpenAiHttpClientBuilderCustomizer { invoked.set(true) }

        OpenAiCompatibleModelFactory(
            baseUrl = null,
            apiKey = null,
            httpClientCustomizers = listProvider(listOf(customizer)),
        )

        assertTrue(invoked.get(), "customizer must be invoked during factory construction")
    }

    @Test
    fun `multiple customizers are all invoked for each client`() {
        val count = AtomicInteger(0)
        val c1 = OpenAiHttpClientBuilderCustomizer { count.incrementAndGet() }
        val c2 = OpenAiHttpClientBuilderCustomizer { count.incrementAndGet() }

        OpenAiCompatibleModelFactory(
            baseUrl = "http://foobar.example",
            apiKey = "test-key",
            httpClientCustomizers = listProvider(listOf(c1, c2)),
        )

        // 2 customizers × 2 independent clients (sync + async) = 4 invocations
        assertEquals(4, count.get(), "all customizers must be invoked for each independent client")
    }

    @Test
    fun `customizer is invoked independently for sync and async clients`() {
        val count = AtomicInteger(0)
        val customizer = OpenAiHttpClientBuilderCustomizer { count.incrementAndGet() }

        OpenAiCompatibleModelFactory(
            baseUrl = null,
            apiKey = null,
            httpClientCustomizers = listProvider(listOf(customizer)),
        )

        assertEquals(2, count.get(), "customizer must be invoked once per client — sync and async are independent")
    }

    private fun <T> listProvider(beans: List<T>): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getObject(): T = beans.first()
        override fun getIfAvailable(): T? = beans.firstOrNull()
        override fun getIfUnique(): T? = beans.singleOrNull()
        override fun iterator(): MutableIterator<T> = beans.toMutableList().iterator()
        override fun orderedStream(): Stream<T> = beans.stream()
        override fun stream(): Stream<T> = beans.stream()
    }
}
