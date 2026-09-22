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
package com.embabel.common.ai.model.local

import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties.Companion.PREFIX
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How often a local runner is re-asked what it serves.
 *
 * One knob for every runner rather than one per runner: the question "how stale may this answer be"
 * is about the operator's patience after a `pull`, not about which runner did the serving, and three
 * copies of it would drift.
 */
@ConfigurationProperties(prefix = PREFIX)
data class LocalModelDiscoveryProperties(

    /**
     * Whether a locally served model may satisfy a role without having been discovered at startup.
     *
     * Off turns [LocalModelRoleResolver] into a resolver that answers nothing, restoring the
     * startup-only behaviour: a model pulled after boot is invisible until the process comes back.
     * Here for a deployment that would rather pay a restart than have any per-call HTTP at all.
     */
    var enabled: Boolean = true,

    /**
     * How long a list of served models is reused before the runner is asked again.
     *
     * The floor on how long a model that has just been REMOVED keeps resolving, and the ceiling on
     * how much HTTP a bulk ingest adds: at ten seconds, embedding for an hour costs 360 requests to
     * a process on the same machine. Lower it if models come and go faster than that.
     */
    var refreshInterval: Duration = Duration.ofSeconds(10),

    /**
     * The shortest interval at which a model NOT in the cached list can force a fresh look.
     *
     * Without this, a model pulled two seconds ago waits out [refreshInterval] before anything can
     * use it, and "pull then ask" - the whole point - would fail once and work on the retry. With
     * it, a miss re-asks immediately, and this is only what stops a role nobody will ever satisfy
     * from turning every call into a request.
     */
    var missRefreshInterval: Duration = Duration.ofSeconds(1),
) {
    companion object {
        const val PREFIX = "embabel.agent.platform.models.local-discovery"
    }
}
