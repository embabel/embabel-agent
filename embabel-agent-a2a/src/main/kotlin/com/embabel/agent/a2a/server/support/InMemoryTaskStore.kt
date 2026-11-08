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
package com.embabel.agent.a2a.server.support

import io.a2a.spec.Task
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory task persistence for A2A task lifecycle methods.
 * Sufficient for single-instance servers; swap for a persistent store
 * when running multiple instances.
 */
@Service
class InMemoryTaskStore {
    private val tasks = ConcurrentHashMap<String, Task>()

    fun save(task: Task) {
        tasks[task.id] = task
    }

    fun get(id: String): Task? = tasks[id]
}
