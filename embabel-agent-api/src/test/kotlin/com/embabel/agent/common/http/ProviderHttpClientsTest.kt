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
package com.embabel.agent.common.http

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.Logger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.ReactorClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.client.RestClient
import java.time.Duration

class ProviderHttpClientsTest {

    private val logger = mockk<Logger>(relaxed = true)

    private fun resolve(
        providerConnect: Duration? = null,
        providerRead: Duration? = null,
        httpConnect: String = "25s",
        httpRead: String = "5m",
        httpUseReactorNetty: String = "true",
    ) = ProviderHttpClients.resolve(
        provider = "Acme",
        providerConnect = providerConnect,
        providerRead = providerRead,
        httpConnect = httpConnect,
        httpRead = httpRead,
        httpUseReactorNetty = httpUseReactorNetty,
        logger = logger,
    )

    private fun <T : Any> providerOf(name: String, bean: T?, type: Class<T>): ObjectProvider<T> =
        StaticListableBeanFactory().apply { bean?.let { addBean(name, it) } }.getBeanProvider(type)

    /** The request factory a builder carries; RestClient.Builder has no getter for it. */
    private fun RestClient.Builder.requestFactoryOf(): Any? =
        javaClass.getDeclaredField("requestFactory").apply { isAccessible = true }.get(this)

    @Test
    fun `without provider timeouts it uses the http-client ones and keeps the shared transport`() {
        val clients = resolve(httpConnect = "7s", httpRead = "3m")

        assertEquals(Duration.ofSeconds(7), clients.connect)
        assertEquals(Duration.ofMinutes(3), clients.read)
        assertFalse(clients.ownsTransport)
        val shared = RestClient.builder().requestFactory(SimpleClientHttpRequestFactory())
        val builder = clients.restClientBuilder(providerOf("shared", shared, RestClient.Builder::class.java))
        assertTrue(builder.requestFactoryOf() is SimpleClientHttpRequestFactory)
        verify(exactly = 0) { logger.info(any(), *anyVararg()) }
    }

    @Test
    fun `a provider timeout replaces the shared transport and the unset one falls back`() {
        val clients = resolve(providerConnect = Duration.ofSeconds(2), httpRead = "3m")

        assertEquals(Duration.ofSeconds(2), clients.connect)
        assertEquals(Duration.ofMinutes(3), clients.read)
        assertTrue(clients.ownsTransport)
        val shared = RestClient.builder().requestFactory(SimpleClientHttpRequestFactory())
        val builder = clients.restClientBuilder(providerOf("shared", shared, RestClient.Builder::class.java))
        assertTrue(builder.requestFactoryOf() is ReactorClientHttpRequestFactory)
        assertTrue(shared.requestFactoryOf() is SimpleClientHttpRequestFactory, "the shared builder must not change")
        verify(exactly = 1) { logger.info(any(), *anyVararg()) }
        verify(exactly = 0) { logger.warn(any<String>(), any<Any>(), any<Any>()) }
    }

    @Test
    fun `without a shared builder it falls back to a JDK client`() {
        val builder = resolve().restClientBuilder(providerOf("none", null, RestClient.Builder::class.java))

        assertTrue(builder.requestFactoryOf() is JdkClientHttpRequestFactory)
    }

    @Test
    fun `reactor-netty is used unless opted out`() {
        val clients = resolve(providerRead = Duration.ofSeconds(1), httpUseReactorNetty = "TRUE")

        assertTrue(clients.reactorNetty)
        assertTrue(clients.requestFactory() is ReactorClientHttpRequestFactory)
        assertTrue(clients.connector() is ReactorClientHttpConnector)
    }

    @Test
    fun `opting out of reactor-netty picks JDK clients and warns`() {
        val clients = resolve(providerRead = Duration.ofSeconds(1), httpUseReactorNetty = "false")

        assertFalse(clients.reactorNetty)
        assertTrue(clients.requestFactory() is JdkClientHttpRequestFactory)
        assertTrue(clients.connector() is JdkClientHttpConnector)
        verify(exactly = 1) { logger.warn(any<String>(), any<Any>(), any<Any>()) }
    }

    @Test
    fun `a malformed http-client timeout names the property`() {
        val failure = assertThrows<IllegalArgumentException> { resolve(httpRead = "soon") }

        assertTrue(failure.message.orEmpty().contains(ProviderHttpClients.HTTP_READ_TIMEOUT), failure.message)
        assertSame(IllegalArgumentException::class.java, failure.cause?.javaClass)
    }

    @Test
    fun `a malformed http-client timeout fails even when the provider replaces it`() {
        val failure = assertThrows<IllegalArgumentException> {
            resolve(providerConnect = Duration.ofSeconds(1), httpConnect = "soon")
        }

        assertTrue(failure.message.orEmpty().contains(ProviderHttpClients.HTTP_CONNECT_TIMEOUT), failure.message)
    }
}
