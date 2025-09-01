/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.config.ContextRepositoryProperties
import com.embabel.agent.spi.Context
import com.embabel.agent.spi.ContextRepository
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory implementation of [ContextRepository] with configurable window size
 * to prevent memory overflow by evicting the oldest entries when the limit is reached.
 */
class InMemoryContextRepository(
    private val properties: ContextRepositoryProperties = ContextRepositoryProperties(),
) : ContextRepository {

    private val map: ConcurrentHashMap<String, Context> = ConcurrentHashMap()
    private val accessOrder: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    private val lock = ReentrantReadWriteLock()

    override fun create(): Context {
        return save(SimpleContext())
    }

    override fun findById(id: String): Context? = lock.read {
        map[id]
    }

    override fun save(context: Context): Context = lock.write {
        val persistentId = context.id ?: UUID.randomUUID().toString()

        // If this process already exists, remove it from access order to re-add at end
        if (map.containsKey(persistentId)) {
            accessOrder.remove(persistentId)
        }

        map[persistentId] = context
        accessOrder.offer(persistentId)

        while (map.size > properties.windowSize) {
            val oldestId = accessOrder.poll()
            if (oldestId != null) {
                map.remove(oldestId)
            }
        }
        context.withId(persistentId)
    }

    override fun delete(context: Context) {
        lock.write {
            val contextId = context.id
            map.remove(contextId)
            accessOrder.remove(contextId)
        }
    }

    /**
     * Get current size of the repository for testing purposes.
     */
    fun size(): Int = lock.read { map.size }

    /**
     * Clear all entries from the repository for testing purposes.
     */
    fun clear() = lock.write {
        map.clear()
        accessOrder.clear()
    }
}
