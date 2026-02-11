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
package com.embabel.agent.rag.service.spring

import com.embabel.agent.filter.PropertyFilter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter

class SpringAiFilterConversionTest {

    @Nested
    inner class SimpleFilterConversionTests {

        @Test
        fun `Eq converts to EQ expression`() {
            val filter = PropertyFilter.Eq("owner", "alice")

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.EQ, expression.type())
            assertEquals("owner", (expression.left() as Filter.Key).key())
            assertEquals("alice", (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Ne converts to NE expression`() {
            val filter = PropertyFilter.Ne("status", "deleted")

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.NE, expression.type())
            assertEquals("status", (expression.left() as Filter.Key).key())
            assertEquals("deleted", (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Gt converts to GT expression`() {
            val filter = PropertyFilter.Gt("score", 0.5)

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.GT, expression.type())
            assertEquals("score", (expression.left() as Filter.Key).key())
            assertEquals(0.5, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Gte converts to GTE expression`() {
            val filter = PropertyFilter.Gte("count", 10)

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.GTE, expression.type())
            assertEquals("count", (expression.left() as Filter.Key).key())
            assertEquals(10, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Lt converts to LT expression`() {
            val filter = PropertyFilter.Lt("score", 0.3)

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.LT, expression.type())
            assertEquals("score", (expression.left() as Filter.Key).key())
            assertEquals(0.3, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Lte converts to LTE expression`() {
            val filter = PropertyFilter.Lte("priority", 5)

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.LTE, expression.type())
            assertEquals("priority", (expression.left() as Filter.Key).key())
            assertEquals(5, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `In converts to IN expression`() {
            val filter = PropertyFilter.In("category", listOf("tech", "science"))

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.IN, expression.type())
            assertEquals("category", (expression.left() as Filter.Key).key())
            assertEquals(listOf("tech", "science"), (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Nin converts to NIN expression`() {
            val filter = PropertyFilter.Nin("status", listOf("deleted", "archived"))

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.NIN, expression.type())
            assertEquals("status", (expression.left() as Filter.Key).key())
            assertEquals(listOf("deleted", "archived"), (expression.right() as Filter.Value).value())
        }

        @Test
        fun `Contains converts to EQ expression as fallback`() {
            // Spring AI doesn't have CONTAINS, we fall back to EQ
            val filter = PropertyFilter.Contains("description", "machine")

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.EQ, expression.type())
            assertEquals("description", (expression.left() as Filter.Key).key())
            assertEquals("machine", (expression.right() as Filter.Value).value())
        }
    }

    @Nested
    inner class LogicalFilterConversionTests {

        @Test
        fun `And with two filters converts to AND expression`() {
            val filter = PropertyFilter.And(
                PropertyFilter.Eq("owner", "alice"),
                PropertyFilter.Eq("status", "active")
            )

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.AND, expression.type())

            val left = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.EQ, left.type())
            assertEquals("owner", (left.left() as Filter.Key).key())

            val right = expression.right() as Filter.Expression
            assertEquals(Filter.ExpressionType.EQ, right.type())
            assertEquals("status", (right.left() as Filter.Key).key())
        }

        @Test
        fun `And with multiple filters chains correctly`() {
            val filter = PropertyFilter.And(
                PropertyFilter.Eq("a", "1"),
                PropertyFilter.Eq("b", "2"),
                PropertyFilter.Eq("c", "3")
            )

            val expression = filter.toSpringAiExpression()

            // Should be ((a AND b) AND c)
            assertEquals(Filter.ExpressionType.AND, expression.type())

            // The result of reduce is left-associative: ((a AND b) AND c)
            val leftAnd = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.AND, leftAnd.type())
        }

        @Test
        fun `Or with two filters converts to OR expression`() {
            val filter = PropertyFilter.Or(
                PropertyFilter.Eq("owner", "alice"),
                PropertyFilter.Eq("owner", "bob")
            )

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.OR, expression.type())

            val left = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.EQ, left.type())

            val right = expression.right() as Filter.Expression
            assertEquals(Filter.ExpressionType.EQ, right.type())
        }

        @Test
        fun `Not converts to NOT expression`() {
            val filter = PropertyFilter.Not(PropertyFilter.Eq("owner", "alice"))

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.NOT, expression.type())

            val inner = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.EQ, inner.type())
            assertEquals("owner", (inner.left() as Filter.Key).key())
        }
    }

    @Nested
    inner class ComplexNestedFilterTests {

        @Test
        fun `complex nested filter converts correctly`() {
            // (owner == "alice" AND status == "active") OR role == "admin"
            val filter = PropertyFilter.Or(
                PropertyFilter.And(
                    PropertyFilter.Eq("owner", "alice"),
                    PropertyFilter.Eq("status", "active")
                ),
                PropertyFilter.Eq("role", "admin")
            )

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.OR, expression.type())

            val andExpr = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.AND, andExpr.type())

            val adminExpr = expression.right() as Filter.Expression
            assertEquals(Filter.ExpressionType.EQ, adminExpr.type())
            assertEquals("role", (adminExpr.left() as Filter.Key).key())
        }

        @Test
        fun `NOT with AND converts correctly`() {
            // NOT (owner == "alice" AND status == "deleted")
            val filter = PropertyFilter.Not(
                PropertyFilter.And(
                    PropertyFilter.Eq("owner", "alice"),
                    PropertyFilter.Eq("status", "deleted")
                )
            )

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.NOT, expression.type())

            val andExpr = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.AND, andExpr.type())
        }

        @Test
        fun `deeply nested filter converts correctly`() {
            // (a AND (b OR c)) AND d
            val filter = PropertyFilter.And(
                PropertyFilter.And(
                    PropertyFilter.Eq("a", "1"),
                    PropertyFilter.Or(
                        PropertyFilter.Eq("b", "2"),
                        PropertyFilter.Eq("c", "3")
                    )
                ),
                PropertyFilter.Eq("d", "4")
            )

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.AND, expression.type())

            val leftAnd = expression.left() as Filter.Expression
            assertEquals(Filter.ExpressionType.AND, leftAnd.type())

            val orExpr = leftAnd.right() as Filter.Expression
            assertEquals(Filter.ExpressionType.OR, orExpr.type())
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `filter with numeric integer value`() {
            val filter = PropertyFilter.Eq("count", 42)

            val expression = filter.toSpringAiExpression()

            assertEquals(42, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `filter with numeric double value`() {
            val filter = PropertyFilter.Eq("score", 0.95)

            val expression = filter.toSpringAiExpression()

            assertEquals(0.95, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `filter with boolean value`() {
            val filter = PropertyFilter.Eq("isActive", true)

            val expression = filter.toSpringAiExpression()

            assertEquals(true, (expression.right() as Filter.Value).value())
        }

        @Test
        fun `In filter with empty list`() {
            val filter = PropertyFilter.In("category", emptyList())

            val expression = filter.toSpringAiExpression()

            assertEquals(Filter.ExpressionType.IN, expression.type())
            assertEquals(emptyList<Any>(), (expression.right() as Filter.Value).value())
        }

        @Test
        fun `In filter with mixed types`() {
            val filter = PropertyFilter.In("value", listOf("text", 42, true))

            val expression = filter.toSpringAiExpression()

            assertEquals(listOf("text", 42, true), (expression.right() as Filter.Value).value())
        }
    }
}
