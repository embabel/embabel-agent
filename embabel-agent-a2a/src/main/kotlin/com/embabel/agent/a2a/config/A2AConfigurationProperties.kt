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

@ConfigurationProperties(prefix = "embabel.agent.platform.a2a")
data class A2AConfigurationProperties(
    val server: Server = Server(),
) {
    /**
     * @param taskRetention how long a finished task stays available to tasks/get and resubscription
     */
    data class Server(
        val taskRetention: Duration = DEFAULT_TASK_RETENTION,
    )

    companion object {
        @JvmField
        val DEFAULT_TASK_RETENTION: Duration = Duration.ofHours(1)
    }
}
