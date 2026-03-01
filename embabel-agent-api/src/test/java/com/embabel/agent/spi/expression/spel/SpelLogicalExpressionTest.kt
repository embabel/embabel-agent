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
package com.embabel.agent.spi.expression.spel

import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

data class Person(
    val name: String,
    val age: Int,
)

data class Car(
    val model: String,
    val year: Int,
)

class SpelLogicalExpressionTest {

    @Test
    fun `evaluate simple comparison returns TRUE`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Alice", 30)
        blackboard += person

        val expression = SpelLogicalExpression("person.age > 20")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate simple comparison returns FALSE`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Bob", 15)
        blackboard += person

        val expression = SpelLogicalExpression("person.age > 20")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.FALSE, result)
    }

    @Test
    fun `evaluate with missing object returns UNKNOWN`() {
        val blackboard = InMemoryBlackboard()

        val expression = SpelLogicalExpression("person.age > 20")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.UNKNOWN, result)
    }

    @Test
    fun `evaluate string equality returns TRUE`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Charlie", 25)
        blackboard += person

        val expression = SpelLogicalExpression("person.name == 'Charlie'")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate string equality returns FALSE`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("David", 28)
        blackboard += person

        val expression = SpelLogicalExpression("person.name == 'Charlie'")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.FALSE, result)
    }

    @Test
    fun `evaluate with explicit binding name`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Eve", 35)
        blackboard["employee"] = person

        val expression = SpelLogicalExpression("employee.age >= 18")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate multiple objects uses simple class name`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Frank", 40)
        val car = Car("Tesla", 2023)
        blackboard += person
        blackboard += car

        val expression = SpelLogicalExpression("car.year > 2020")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate complex AND expression`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Grace", 25)
        blackboard += person

        val expression = SpelLogicalExpression("person.age >= 18 && person.age < 65")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate complex OR expression`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Henry", 70)
        blackboard += person

        val expression = SpelLogicalExpression("person.age < 18 || person.age >= 65")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate invalid expression returns UNKNOWN`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Ivy", 22)
        blackboard += person

        val expression = SpelLogicalExpression("person.invalidProperty > 10")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.UNKNOWN, result)
    }

    @Test
    fun `evaluate with numbers only returns UNKNOWN`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Jack", 30)
        blackboard += person

        // Non-boolean result should return UNKNOWN
        val expression = SpelLogicalExpression("person.age + 10")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.UNKNOWN, result)
    }

    @Test
    fun `evaluate less than or equal`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Kate", 21)
        blackboard += person

        val expression = SpelLogicalExpression("person.age <= 21")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate not equals returns TRUE`() {
        val blackboard = InMemoryBlackboard()
        val person = Person("Leo", 30)
        blackboard += person

        val expression = SpelLogicalExpression("person.name != 'Mike'")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate map property access via dot notation returns TRUE`() {
        val blackboard = InMemoryBlackboard()
        val order = linkedMapOf("orderId" to "ORD-123", "total" to 99.95)
        blackboard["orderCreatedEvent"] = order

        val expression = SpelLogicalExpression("orderCreatedEvent.orderId != null")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate map numeric comparison via dot notation`() {
        val blackboard = InMemoryBlackboard()
        val order = linkedMapOf("orderId" to "ORD-456", "total" to 150.0)
        blackboard["order"] = order

        val expression = SpelLogicalExpression("order.total > 100")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `evaluate map string contains via dot notation`() {
        val blackboard = InMemoryBlackboard()
        val email = linkedMapOf("sender" to "Richard Beck", "subject" to "Hello")
        blackboard["email"] = email

        val expression = SpelLogicalExpression("email.sender.contains('Richard')")
        val result = expression.evaluate(blackboard)

        assertEquals(ConditionDetermination.TRUE, result)
    }
}
