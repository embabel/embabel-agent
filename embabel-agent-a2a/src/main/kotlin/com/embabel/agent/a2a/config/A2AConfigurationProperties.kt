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

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * A2A settings, bound from `embabel.agent.platform.a2a.*`.
 *
 * Registered by both the A2A server configuration and the A2A client auto-configuration,
 * so client-only applications can bind [client] without running the server.
 *
 * @param client settings for calls this application makes to remote A2A agents
 * @param server settings for this application's own A2A endpoints
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.a2a")
data class A2AConfigurationProperties(
    val client: Client = Client(),
    val server: Server = Server(),
) {
    /**
     * Timeouts for calls this application makes to remote A2A agents,
     * bound from `embabel.agent.platform.a2a.client.*`.
     *
     * @param timeoutSeconds read timeout (`timeout-seconds`): the longest the caller waits for a response.
     * A non-streaming `message/send` returns only once the remote agent has finished its whole run, often
     * several LLM calls, so this is effectively the maximum duration of a remote agent run.
     * Defaults to [DEFAULT_CLIENT_TIMEOUT_SECONDS].
     * @param connectTimeoutSeconds connect timeout (`connect-timeout-seconds`): the longest the caller waits
     * to open a connection, so an unreachable agent fails fast instead of waiting for the read timeout.
     * Defaults to [DEFAULT_CLIENT_CONNECT_TIMEOUT_SECONDS].
     */
    data class Client(
        val timeoutSeconds: Long = DEFAULT_CLIENT_TIMEOUT_SECONDS,
        val connectTimeoutSeconds: Long = DEFAULT_CLIENT_CONNECT_TIMEOUT_SECONDS,
    )

    /**
     * Settings for this application's own A2A endpoints, bound from `embabel.agent.platform.a2a.server.*`.
     *
     * @param preferredTransport transport advertised in agent cards (`preferred-transport`).
     * Defaults to [DEFAULT_PREFERRED_TRANSPORT].
     * @param taskRetention how long a finished task stays available to `tasks/get` and resubscription
     * (`task-retention`). Defaults to [DEFAULT_TASK_RETENTION].
     */
    data class Server(
        val preferredTransport: String = DEFAULT_PREFERRED_TRANSPORT,
        val taskRetention: Duration = DEFAULT_TASK_RETENTION,
    )

    companion object {

        /** Default read timeout for remote A2A calls, in seconds: five minutes, enough for a typical whole agent run. */
        const val DEFAULT_CLIENT_TIMEOUT_SECONDS: Long = 300

        /** Default connect timeout for remote A2A calls, in seconds. */
        const val DEFAULT_CLIENT_CONNECT_TIMEOUT_SECONDS: Long = 10

        /**
         * Default transport advertised in agent cards: JSON-RPC 2.0, the transport this server implements.
         * Same value as `io.a2a.spec.TransportProtocol.JSONRPC.asString()`, kept as a literal so it is a compile-time constant.
         */
        const val DEFAULT_PREFERRED_TRANSPORT: String = "JSONRPC"

        /** Default time a finished task stays available to `tasks/get` and resubscription before eviction. */
        @JvmField
        val DEFAULT_TASK_RETENTION: Duration = Duration.ofHours(1)
    }
}
