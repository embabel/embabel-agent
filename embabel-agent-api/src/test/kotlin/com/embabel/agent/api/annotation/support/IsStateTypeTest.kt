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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.State
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsStateTypeTest {

    @State
    private class DirectStateClass

    private class NotAStateClass

    @State
    private open class BaseStateClass

    private open class SubclassOfState : BaseStateClass()

    private class GrandchildOfState : SubclassOfState()

    @State
    private open class AnotherStateClass

    private open class IntermediateNonStateClass : AnotherStateClass()

    private class DeepSubclass : IntermediateNonStateClass()

    // Interface inheritance tests
    @State
    private interface StateInterface

    private class ImplementsStateInterface : StateInterface

    private interface NonStateInterface

    private class ImplementsNonStateInterface : NonStateInterface

    @State
    private interface BaseStateInterface

    private interface ExtendedStateInterface : BaseStateInterface

    private class ImplementsExtendedStateInterface : ExtendedStateInterface

    // Multiple interface inheritance
    private interface RegularInterface

    private class ImplementsMultipleIncludingState : RegularInterface, StateInterface

    // Class extends class and implements @State interface
    private open class RegularClass

    private class ExtendsClassImplementsStateInterface : RegularClass(), StateInterface

    @Test
    fun `returns true for class directly annotated with State`() {
        assertTrue(isStateType(DirectStateClass::class.java))
    }

    @Test
    fun `returns false for class without State annotation`() {
        assertFalse(isStateType(NotAStateClass::class.java))
    }

    @Test
    fun `returns true for subclass of State-annotated class`() {
        assertTrue(isStateType(SubclassOfState::class.java))
    }

    @Test
    fun `returns true for grandchild of State-annotated class`() {
        assertTrue(isStateType(GrandchildOfState::class.java))
    }

    @Test
    fun `returns true for deep subclass with intermediate non-annotated class`() {
        assertTrue(isStateType(DeepSubclass::class.java))
    }

    @Test
    fun `returns false for Any class`() {
        assertFalse(isStateType(Any::class.java))
    }

    @Test
    fun `returns false for primitive wrapper types`() {
        assertFalse(isStateType(String::class.java))
        assertFalse(isStateType(Int::class.java))
    }

    // Interface inheritance tests

    @Test
    fun `returns true for interface directly annotated with State`() {
        assertTrue(isStateType(StateInterface::class.java))
    }

    @Test
    fun `returns true for class implementing State-annotated interface`() {
        assertTrue(isStateType(ImplementsStateInterface::class.java))
    }

    @Test
    fun `returns false for class implementing non-State interface`() {
        assertFalse(isStateType(ImplementsNonStateInterface::class.java))
    }

    @Test
    fun `returns true for class implementing interface that extends State-annotated interface`() {
        assertTrue(isStateType(ImplementsExtendedStateInterface::class.java))
    }

    @Test
    fun `returns true for class implementing multiple interfaces including State`() {
        assertTrue(isStateType(ImplementsMultipleIncludingState::class.java))
    }

    @Test
    fun `returns true for class extending regular class and implementing State interface`() {
        assertTrue(isStateType(ExtendsClassImplementsStateInterface::class.java))
    }
}
