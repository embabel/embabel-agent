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
package com.embabel.agent.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class InMemoryCrudRepositoryTest {

    private lateinit var repository: InMemoryCrudRepository<TestEntity>

    data class TestEntity(val id: String? = null, val name: String, val value: Int)

    @BeforeEach
    fun setup() {
        repository = InMemoryCrudRepository(
            idGetter = { it.id },
            idSetter = { entity, id -> entity.copy(id = id) }
        )
    }

    @Test
    fun testSaveNewEntity() {
        val entity = TestEntity(name = "test", value = 42)

        val saved = repository.save(entity)

        assertNotNull(saved.id)
        assertEquals("test", saved.name)
        assertEquals(42, saved.value)
        assertEquals(1, repository.count())
    }

    @Test
    fun testSaveExistingEntity() {
        val entity = TestEntity(id = "existing-id", name = "test", value = 42)

        val saved = repository.save(entity)

        assertEquals("existing-id", saved.id)
        assertEquals("test", saved.name)
        assertEquals(42, saved.value)
        assertEquals(1, repository.count())
    }

    @Test
    fun testSaveAll() {
        val entities = listOf(
            TestEntity(name = "first", value = 1),
            TestEntity(name = "second", value = 2)
        )

        val saved = repository.saveAll(entities)

        assertEquals(2, saved.count())
        assertEquals(2, repository.count())
        saved.forEach { assertNotNull(it.id) }
    }

    @Test
    fun testFindById() {
        val entity = TestEntity(name = "test", value = 42)
        val saved = repository.save(entity)

        val found = repository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals(saved, found.get())
    }

    @Test
    fun testFindByIdNotFound() {
        val found = repository.findById("non-existent")

        assertFalse(found.isPresent)
    }

    @Test
    fun testExistsById() {
        val entity = TestEntity(name = "test", value = 42)
        val saved = repository.save(entity)

        assertTrue(repository.existsById(saved.id!!))
        assertFalse(repository.existsById("non-existent"))
    }

    @Test
    fun testFindAll() {
        val entities = listOf(
            TestEntity(name = "first", value = 1),
            TestEntity(name = "second", value = 2),
            TestEntity(name = "third", value = 3)
        )
        repository.saveAll(entities)

        val all = repository.findAll().toList()

        assertEquals(3, all.size)
        assertTrue(all.any { it.name == "first" })
        assertTrue(all.any { it.name == "second" })
        assertTrue(all.any { it.name == "third" })
    }

    @Test
    fun testFindAllByIds() {
        val entities = listOf(
            TestEntity(name = "first", value = 1),
            TestEntity(name = "second", value = 2),
            TestEntity(name = "third", value = 3)
        )
        val saved = repository.saveAll(entities).toList()
        val idsToFind = listOf(saved[0].id!!, saved[2].id!!)

        val found = repository.findAllById(idsToFind).toList()

        assertEquals(2, found.size)
        assertTrue(found.any { it.name == "first" })
        assertTrue(found.any { it.name == "third" })
        assertFalse(found.any { it.name == "second" })
    }

    @Test
    fun testFindAllByIdsWithNonExistentIds() {
        val entity = TestEntity(name = "test", value = 42)
        val saved = repository.save(entity)
        val idsToFind = listOf(saved.id!!, "non-existent")

        val found = repository.findAllById(idsToFind).toList()

        assertEquals(1, found.size)
        assertEquals("test", found[0].name)
    }

    @Test
    fun testCount() {
        assertEquals(0, repository.count())

        repository.save(TestEntity(name = "first", value = 1))
        assertEquals(1, repository.count())

        repository.save(TestEntity(name = "second", value = 2))
        assertEquals(2, repository.count())
    }

    @Test
    fun testDeleteById() {
        val entity = TestEntity(name = "test", value = 42)
        val saved = repository.save(entity)
        assertEquals(1, repository.count())

        repository.deleteById(saved.id!!)

        assertEquals(0, repository.count())
        assertFalse(repository.existsById(saved.id!!))
    }

    @Test
    fun testDeleteByIdNonExistent() {
        repository.deleteById("non-existent")

        assertEquals(0, repository.count())
    }

    @Test
    fun testDelete() {
        val entity = TestEntity(name = "test", value = 42)
        val saved = repository.save(entity)
        assertEquals(1, repository.count())

        repository.delete(saved)

        assertEquals(0, repository.count())
        assertFalse(repository.existsById(saved.id!!))
    }

    @Test
    fun testDeleteEntityWithoutId() {
        val entity = TestEntity(name = "test", value = 42)

        repository.delete(entity)

        assertEquals(0, repository.count())
    }

    @Test
    fun testDeleteAllByIds() {
        val entities = listOf(
            TestEntity(name = "first", value = 1),
            TestEntity(name = "second", value = 2),
            TestEntity(name = "third", value = 3)
        )
        val saved = repository.saveAll(entities).toList()
        assertEquals(3, repository.count())

        val idsToDelete = listOf(saved[0].id!!, saved[2].id!!)
        repository.deleteAllById(idsToDelete)

        assertEquals(1, repository.count())
        assertTrue(repository.existsById(saved[1].id!!))
        assertFalse(repository.existsById(saved[0].id!!))
        assertFalse(repository.existsById(saved[2].id!!))
    }

    @Test
    fun testDeleteAllByEntities() {
        val entities = listOf(
            TestEntity(name = "first", value = 1),
            TestEntity(name = "second", value = 2),
            TestEntity(name = "third", value = 3)
        )
        val saved = repository.saveAll(entities).toList()
        assertEquals(3, repository.count())

        val entitiesToDelete = listOf(saved[0], saved[2])
        repository.deleteAll(entitiesToDelete)

        assertEquals(1, repository.count())
        assertTrue(repository.existsById(saved[1].id!!))
        assertFalse(repository.existsById(saved[0].id!!))
        assertFalse(repository.existsById(saved[2].id!!))
    }

    @Test
    fun testDeleteAll() {
        val entities = listOf(
            TestEntity(name = "first", value = 1),
            TestEntity(name = "second", value = 2),
            TestEntity(name = "third", value = 3)
        )
        repository.saveAll(entities)
        assertEquals(3, repository.count())

        repository.deleteAll()

        assertEquals(0, repository.count())
        assertTrue(repository.findAll().toList().isEmpty())
    }

    @Test
    fun testUpdateExistingEntity() {
        val entity = TestEntity(name = "original", value = 42)
        val saved = repository.save(entity)

        val updated = saved.copy(name = "updated", value = 100)
        val savedUpdated = repository.save(updated)

        assertEquals(saved.id, savedUpdated.id)
        assertEquals("updated", savedUpdated.name)
        assertEquals(100, savedUpdated.value)
        assertEquals(1, repository.count())
    }

    @Test
    fun testConcurrentModification() {
        val entities = (1..100).map { TestEntity(name = "entity$it", value = it) }

        entities.parallelStream().forEach { repository.save(it) }

        assertEquals(100, repository.count())
        assertEquals(100, repository.findAll().toList().size)
    }

    @Test
    fun testIdGenerationUniqueness() {
        val entities = (1..10).map { TestEntity(name = "entity$it", value = it) }
        val saved = repository.saveAll(entities).toList()

        val ids = saved.map { it.id }.toSet()
        assertEquals(10, ids.size)
    }
}
