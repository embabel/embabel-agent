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
package com.embabel.agent.spi.support.http

import io.netty.channel.ChannelOption
import org.slf4j.Logger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.convert.DurationStyle
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.ReactorClientHttpRequestFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.net.http.HttpClient as JdkHttpClient
import java.time.Duration

/**
 * The HTTP clients of a model provider that accepts `connect-timeout` and `read-timeout` under its
 * own prefix, falling back to `embabel.agent.platform.http-client.*` for whichever it leaves unset.
 *
 * A provider normally clones the shared `aiModelRestClientBuilder` / `aiModelWebClientBuilder`
 * beans. Those carry the http-client timeouts and cannot be changed, so a provider that sets a
 * timeout of its own ([ownsTransport]) replaces their transport with its own client. Anything else
 * customised on that transport, such as a proxy or TLS, then does not apply to the provider.
 *
 * The replacement is reactor-netty, configured as NettyClientAutoConfiguration configures the shared
 * client: [read] is netty's response timeout, which bounds each wait between reads, so a stream that
 * stalls mid-body fails too. When the application opted out of reactor-netty ([reactorNetty] false)
 * it is the JDK client instead, whose [read] bounds only the wait for response headers.
 *
 * Needs `spring-webflux` and `reactor-netty-http` on the classpath, which this module declares as
 * optional.
 */
class ProviderHttpClients private constructor(
    /** How long to wait to connect: the provider's timeout, else the http-client one. */
    val connect: Duration,
    /** How long to wait for a response: the provider's timeout, else the http-client one. */
    val read: Duration,
    /** Whether the provider set a timeout of its own, and so uses its own transport. */
    val ownsTransport: Boolean,
    /** Whether that transport is reactor-netty rather than the JDK client. */
    val reactorNetty: Boolean,
) {

    /**
     * A clone of [shared], with this provider's transport when it [ownsTransport]. When no shared
     * builder is available, a JDK-backed one carrying [connect] and [read], so a slow response is
     * not aborted at the ~10s default a bare reactor-netty request factory would impose.
     */
    fun restClientBuilder(shared: ObjectProvider<RestClient.Builder>): RestClient.Builder =
        shared.getIfAvailable { RestClient.builder().requestFactory(jdkRequestFactory()) }
            .clone()
            .let { if (ownsTransport) it.requestFactory(requestFactory()) else it }

    /** A clone of [shared], or a plain builder, with this provider's transport when it [ownsTransport]. */
    fun webClientBuilder(shared: ObjectProvider<WebClient.Builder>): WebClient.Builder =
        shared.getIfAvailable(WebClient::builder)
            .clone()
            .let { if (ownsTransport) it.clientConnector(connector()) else it }

    /**
     * The request factory for blocking calls that [restClientBuilder] installs when the provider
     * [ownsTransport]. Public so a provider's tests can check which transport it chose.
     */
    fun requestFactory(): ClientHttpRequestFactory =
        if (reactorNetty) ReactorClientHttpRequestFactory(nettyClient()) else jdkRequestFactory()

    /**
     * The connector for streamed calls that [webClientBuilder] installs when the provider
     * [ownsTransport]. Public so a provider's tests can check which transport it chose.
     */
    fun connector(): ClientHttpConnector =
        if (reactorNetty) {
            ReactorClientHttpConnector(nettyClient())
        } else {
            JdkClientHttpConnector(jdkClient()).apply { setReadTimeout(read) }
        }

    private fun jdkRequestFactory(): ClientHttpRequestFactory =
        JdkClientHttpRequestFactory(jdkClient()).apply { setReadTimeout(read) }

    private fun jdkClient(): JdkHttpClient = JdkHttpClient.newBuilder().connectTimeout(connect).build()

    private fun nettyClient(): HttpClient =
        HttpClient.create()
            .followRedirect(true)
            .responseTimeout(read)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connect.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    companion object {

        const val HTTP_CONNECT_TIMEOUT = "embabel.agent.platform.http-client.connect-timeout"
        const val HTTP_READ_TIMEOUT = "embabel.agent.platform.http-client.read-timeout"
        const val HTTP_USE_REACTOR_NETTY = "embabel.agent.platform.http-client.use-reactor-netty"

        /** The http-client connect timeout's default, as NettyClientAutoConfiguration has it. */
        const val DEFAULT_CONNECT_TIMEOUT = "25s"

        /** The http-client read timeout's default, as NettyClientAutoConfiguration has it. */
        const val DEFAULT_READ_TIMEOUT = "5m"

        /**
         * Resolves [provider]'s clients from its own timeouts and the http-client properties' raw
         * values, and logs once, at INFO, when the provider replaces the shared transport, and at
         * WARN when that replacement is the JDK client.
         *
         * [httpUseReactorNetty] is read as NettyClientAutoConfiguration's `@ConditionalOnProperty`
         * reads it: only `true`, in any case, is true.
         *
         * @throws IllegalArgumentException naming the property, when [httpConnect] or [httpRead] is
         * not a duration, even if the provider's own timeout replaces it
         */
        @JvmStatic
        fun resolve(
            provider: String,
            providerConnect: Duration?,
            providerRead: Duration?,
            httpConnect: String,
            httpRead: String,
            httpUseReactorNetty: String,
            logger: Logger,
        ): ProviderHttpClients {
            // Parse both even when the provider replaces them, so a malformed value always fails.
            val httpConnectTimeout = parseTimeout(HTTP_CONNECT_TIMEOUT, httpConnect)
            val httpReadTimeout = parseTimeout(HTTP_READ_TIMEOUT, httpRead)
            return ProviderHttpClients(
                connect = providerConnect ?: httpConnectTimeout,
                read = providerRead ?: httpReadTimeout,
                ownsTransport = providerConnect != null || providerRead != null,
                reactorNetty = httpUseReactorNetty.equals("true", ignoreCase = true),
            ).also { it.logTransport(provider, logger) }
        }

        private fun parseTimeout(property: String, value: String): Duration =
            try {
                DurationStyle.detectAndParse(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$property must be a duration such as 30s or 5m, but was '$value'", e)
            }
    }

    private fun logTransport(provider: String, logger: Logger) {
        if (!ownsTransport) return
        logger.info(
            "{} uses its own HTTP client (connect timeout {}, read timeout {}) in place of the shared " +
                "aiModelRestClientBuilder / aiModelWebClientBuilder transport; proxy or TLS settings on " +
                "those do not apply to it",
            provider, connect, read,
        )
        if (!reactorNetty) {
            logger.warn(
                "{} uses the JDK HTTP client, as {} is false: its read timeout bounds the wait for " +
                    "response headers only, not a stream that stalls mid-body",
                provider, HTTP_USE_REACTOR_NETTY,
            )
        }
    }
}
