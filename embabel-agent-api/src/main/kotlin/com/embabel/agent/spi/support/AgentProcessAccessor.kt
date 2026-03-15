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
package com.embabel.agent.spi.support

import com.embabel.agent.core.AgentProcess
import javax.annotation.concurrent.ThreadSafe

/**
 * Accessor for AgentProcess ThreadLocal storage.
 *
 * Used by [ExecutorAsyncer] to propagate AgentProcess context from the calling thread
 * to worker threads. Each thread has its own ThreadLocal storage - this accessor
 * provides the read/write operations to copy values between threads.
 *
 * Flow:
 * 1. Main thread: [getValue] captures current AgentProcess
 * 2. Worker thread: [setValue] sets the captured value
 * 3. Worker thread: block executes with access to AgentProcess
 * 4. Worker thread: [reset] cleans up to prevent stale values
 */
@ThreadSafe
object AgentProcessAccessor {

    fun getValue(): AgentProcess? = AgentProcess.get()

    fun setValue(value: AgentProcess) {
        AgentProcess.set(value)
    }

    fun reset() {
        AgentProcess.remove()
    }
}
