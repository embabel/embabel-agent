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
package com.embabel.examples.common

import org.springframework.data.repository.CrudRepository
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Spring Data CrudRepository with in memory storage.
 * Not itself intended for production usage, but can be used
 * in demos to minimize dependencies, and ultimately swapped out
 * for serious use.
 */
open class InMemoryCrudRepository<T : Any>(
    private val idGetter: (T) -> String?,
    private val idSetter: ((T, String) -> T),
) : CrudRepository<T, String> {

    private val storage = ConcurrentHashMap<String, T>()

    @Suppress("UNCHECKED_CAST")
    override fun <S : T> save(entity: S): S {
        var savedEntity = entity
        val existingId = idGetter.invoke(entity)
        val id = existingId ?: UUID.randomUUID().toString()
        savedEntity = (if (existingId == null) idSetter.invoke(savedEntity, id) else entity) as S
        storage[id] = savedEntity
        return savedEntity
    }

    override fun <S : T> saveAll(entities: Iterable<S>): Iterable<S> {
        return entities.map { save(it) }
    }

    override fun findById(id: String): Optional<T> {
        return Optional.ofNullable(storage[id])
    }

    override fun existsById(id: String): Boolean {
        return storage.containsKey(id)
    }

    override fun findAll(): Iterable<T> {
        return ArrayList(storage.values)
    }

    override fun findAllById(ids: Iterable<String>): Iterable<T> {
        return ids.mapNotNull { storage[it] }
    }

    override fun count(): Long {
        return storage.size.toLong()
    }

    override fun deleteById(id: String) {
        storage.remove(id)
    }

    override fun delete(entity: T) {
        val id = idGetter.invoke(entity)
        if (id != null) {
            deleteById(id)
        }
    }

    override fun deleteAllById(ids: Iterable<String>) {
        ids.forEach { deleteById(it) }
    }

    override fun deleteAll(entities: Iterable<T>) {
        entities.forEach { delete(it) }
    }

    override fun deleteAll() {
        storage.clear()
    }

}
