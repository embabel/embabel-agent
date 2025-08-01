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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.common.SomeOf
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.support.Dog
import com.embabel.common.util.loggerFor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

data class DogOrPerson(
    val dog: Dog?,
    val person: PersonWithReverseTool?,
) : SomeOf

class BindFieldsToBlackboardTest {

    @Test
    fun `test bind fields to blackboard`() {
        val dog = Dog("Duke")
        val person = PersonWithReverseTool("Alice")
        val dogOrPerson = DogOrPerson(dog, person)
        val bb = InMemoryBlackboard()
        assertEquals(0, bb.objects.size)
        destructureAndBindIfNecessary(dogOrPerson, "name", bb, loggerFor<SomeOf>())
        assertEquals(2, bb.objects.size)
        assertEquals(dog, bb.last(Dog::class.java), "Duke")
        assertEquals(person, bb.last(PersonWithReverseTool::class.java), "Alice")
    }

    @Test
    fun `test null fields not bound`() {
        val dogOrPerson = DogOrPerson(null, null)
        val bb = InMemoryBlackboard()
        assertEquals(0, bb.objects.size)
        destructureAndBindIfNecessary(dogOrPerson, "name", bb, loggerFor<SomeOf>())
        assertEquals(0, bb.objects.size, "Nothing to bind")
    }


}
