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
package com.embabel.agent.support

import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.IoBinding
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Point
import kotlin.test.assertNotNull

/**
 * Abstract test class for testing Blackboard implementations.
 * Subclasses must implement createBlackboard() to provide the specific implementation to test.
 */
abstract class AbstractBlackboardTest {

    /**
     * Factory method to create a blackboard instance for testing.
     * Subclasses override this to test different blackboard implementations.
     */
    protected abstract fun createBlackboard(): Blackboard

    @Nested
    inner class AggregationHandling {
        @Test
        fun `empty blackboard`() {
            val bb = createBlackboard()
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "AllOfTheAbove",
                    DataDictionary.fromClasses(
                        AllOfTheAbove::class.java,
                        UserInput::class.java,
                        PersonWithReverseTool::class.java
                    )
                )
            )
        }

        @Test
        fun `not satisfied`() {
            val bb = createBlackboard()
            bb += UserInput("John is a man")
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "AllOfTheAbove",

                    DataDictionary.fromClasses(
                        AllOfTheAbove::class.java,
                        UserInput::class.java,
                        PersonWithReverseTool::class.java
                    ),
                )
            )
        }

        @Test
        fun satisfied() {
            val bb = createBlackboard()
            bb += UserInput("John is a man")
            bb += PersonWithReverseTool("John")
            val aota = bb.getValue(
                IoBinding.DEFAULT_BINDING,
                "AllOfTheAbove",
                DataDictionary.fromClasses(
                    AllOfTheAbove::class.java,
                    UserInput::class.java,
                    PersonWithReverseTool::class.java
                ),
            )
            assertNotNull(aota)
            aota as AllOfTheAbove
            assertEquals("John", aota.person.name)
            assertEquals("John is a man", aota.userInput.content)

        }

    }

    @Nested
    inner class TypeResolution {

        @Test
        fun `empty blackboard, no domain objects`() {
            val bb = createBlackboard()
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Person",
                    DataDictionary.fromClasses()
                )
            )
        }

        @Test
        fun `empty blackboard, relevant domain object`() {
            val bb = createBlackboard()
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Person",
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )
        }

        @Test
        fun `exact type match on it`() {
            val bb = createBlackboard()
            val john = PersonWithReverseTool("John")
            bb += john
            assertEquals(
                john,
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    PersonWithReverseTool::class.java.simpleName,
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )
        }

        @Test
        fun `exact type match on variable name`() {
            val bb = createBlackboard()
            val duke = Dog("Duke")
            bb += duke
            assertEquals(
                duke,
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Dog",
                    DataDictionary.fromClasses(Dog::class.java),
                )
            )
        }


        @Test
        fun `interface type match on variable name`() {
            val bb = createBlackboard()
            val duke = Dog("Duke")
            bb += duke
            assertEquals(
                duke, bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Organism",
                    DataDictionary.fromClasses(Dog::class.java)
                )
            )
        }

        @Test
        fun `superclass type match on variable name`() {
            val bb = createBlackboard()
            val duke = Dog("Duke")
            bb += duke
            assertEquals(
                duke, bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Animal",
                    DataDictionary.fromClasses(Dog::class.java)
                )
            )
        }

        @Test
        fun `no type match`() {
            val bb = createBlackboard()
            val john = PersonWithReverseTool("John")
            bb += john
            assertNull(
                bb.getValue(
                    "person",
                    "Point",
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java, Point::class.java)
                )
            )
        }

    }

    @Nested
    inner class HideOperations {

        @Test
        fun `hide prevents retrieval by getValue`() {
            val bb = createBlackboard()
            val john = PersonWithReverseTool("John")
            bb += john

            // Verify object can be retrieved before hiding
            assertEquals(
                john,
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    PersonWithReverseTool::class.java.simpleName,
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )

            // Hide the object
            bb.hide(john)

            // Verify object cannot be retrieved after hiding
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    PersonWithReverseTool::class.java.simpleName,
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )
        }

        @Test
        fun `hide prevents retrieval by type`() {
            val bb = createBlackboard()
            val duke = Dog("Duke")
            bb += duke

            // Verify retrieval works before hiding
            assertEquals(
                duke, bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Dog",
                    DataDictionary.fromClasses(Dog::class.java)
                )
            )

            // Hide the object
            bb.hide(duke)

            // Verify retrieval fails after hiding
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Dog",
                    DataDictionary.fromClasses(Dog::class.java)
                )
            )
        }

        @Test
        fun `hide specific object leaves others visible`() {
            val bb = createBlackboard()
            val john = PersonWithReverseTool("John")
            val jane = PersonWithReverseTool("Jane")
            bb += john
            bb += jane

            // Hide only john
            bb.hide(john)

            // jane should still be retrievable
            val retrieved = bb.getValue(
                IoBinding.DEFAULT_BINDING,
                PersonWithReverseTool::class.java.simpleName,
                DataDictionary.fromClasses(PersonWithReverseTool::class.java)
            )
            assertNotNull(retrieved)
            assertEquals(jane, retrieved)
        }

        @Test
        fun `hide multiple objects`() {
            val bb = createBlackboard()
            val duke = Dog("Duke")
            val rex = Dog("Rex")
            val john = PersonWithReverseTool("John")
            bb += duke
            bb += rex
            bb += john

            // Hide both dogs
            bb.hide(duke)
            bb.hide(rex)

            // Dogs should not be retrievable
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    "Dog",
                    DataDictionary.fromClasses(Dog::class.java)
                )
            )

            // Person should still be retrievable
            assertEquals(
                john,
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    PersonWithReverseTool::class.java.simpleName,
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )
        }

        @Test
        fun `hide does not affect aggregation - aggregations use all objects`() {
            val bb = createBlackboard()
            val userInput = UserInput("John is a man")
            val person = PersonWithReverseTool("John")
            bb += userInput
            bb += person

            // Verify aggregation works before hiding
            val aotaBefore = bb.getValue(
                IoBinding.DEFAULT_BINDING,
                "AllOfTheAbove",
                DataDictionary.fromClasses(
                    AllOfTheAbove::class.java,
                    UserInput::class.java,
                    PersonWithReverseTool::class.java
                ),
            )
            assertNotNull(aotaBefore)

            // Hide the person
            bb.hide(person)

            // Note: Aggregations still work even with hidden objects
            // This is by design - aggregations compose from all available objects
            val aotaAfter = bb.getValue(
                IoBinding.DEFAULT_BINDING,
                "AllOfTheAbove",
                DataDictionary.fromClasses(
                    AllOfTheAbove::class.java,
                    UserInput::class.java,
                    PersonWithReverseTool::class.java
                ),
            )
            assertNotNull(aotaAfter)
        }

        @Test
        fun `hide on empty blackboard does not cause error`() {
            val bb = createBlackboard()
            val john = PersonWithReverseTool("John")

            // Should not throw exception
            bb.hide(john)

            // Verify blackboard is still empty
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    PersonWithReverseTool::class.java.simpleName,
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )
        }

        @Test
        fun `hide same object multiple times`() {
            val bb = createBlackboard()
            val john = PersonWithReverseTool("John")
            bb += john

            // Hide multiple times
            bb.hide(john)
            bb.hide(john)
            bb.hide(john)

            // Should still be hidden
            assertNull(
                bb.getValue(
                    IoBinding.DEFAULT_BINDING,
                    PersonWithReverseTool::class.java.simpleName,
                    DataDictionary.fromClasses(PersonWithReverseTool::class.java)
                )
            )
        }
    }
}
