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
package com.embabel.plan.common.condition.prolog

import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@PrologFact(predicatePrefix = "user")
data class User(
    val name: String,
    val role: String,
    val department: String,
    val yearsOfTenure: Int,
    val permissions: List<String> = emptyList(),
)

@PrologFact
data class Expense(
    val id: String,
    val amount: Double,
    val category: String,
)

class PrologIntegrationTest {

    @Test
    fun `TuPrologEngine can evaluate simple queries`() {
        val rules = """
            parent(tom, bob).
            parent(tom, liz).
            parent(bob, ann).
            parent(bob, pat).
            parent(pat, jim).

            grandparent(X, Y) :- parent(X, Z), parent(Z, Y).
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)

        assertTrue(engine.query("parent(tom, bob)"))
        assertTrue(engine.query("grandparent(tom, ann)"))
        assertFalse(engine.query("parent(bob, tom)"))
        assertTrue(engine.query("grandparent(bob, jim)"))  // bob -> pat -> jim
    }

    @Test
    fun `PrologFactConverter converts user to facts`() {
        val user = User(
            name = "alice",
            role = "manager",
            department = "engineering",
            yearsOfTenure = 3,
            permissions = listOf("read", "write"),
        )

        val converter = PrologFactConverter()
        val facts = converter.convertToFacts(user)

        assertTrue(facts.contains("user_name('alice', 'alice')"))
        assertTrue(facts.contains("user_role('alice', 'manager')"))
        assertTrue(facts.contains("user_department('alice', 'engineering')"))
        assertTrue(facts.contains("user_years_of_tenure('alice', 3)"))
        assertTrue(facts.any { it.contains("user_permissions('alice', 'read')") })
        assertTrue(facts.any { it.contains("user_permissions('alice', 'write')") })
    }

    @Test
    fun `PrologFactConverter handles non-annotated objects`() {
        data class NotAnnotated(val name: String)
        val obj = NotAnnotated("test")

        val converter = PrologFactConverter()
        val facts = converter.convertToFacts(obj)

        assertTrue(facts.isEmpty())
    }

    @Test
    fun `PrologLogicalExpression evaluates with user rules`() {
        val rules = """
            can_approve(User) :-
                user_role(User, 'manager'),
                user_years_of_tenure(User, Years),
                Years >= 2.

            can_approve(User) :-
                user_role(User, 'admin').
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val converter = PrologFactConverter()

        val manager = User("alice", "manager", "engineering", 3)
        val facts = converter.convertToFacts(manager)
        val scopedEngine = engine.assertFacts(facts)

        val expression = PrologLogicalExpression("can_approve('alice')", scopedEngine)
        val result = expression.evaluate { ConditionDetermination.UNKNOWN }

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `PrologLogicalExpression evaluates with objects directly`() {
        val rules = """
            can_approve(User) :-
                user_role(User, 'manager'),
                user_years_of_tenure(User, Years),
                Years >= 2.
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val manager = User("alice", "manager", "engineering", 3)

        val expression = PrologLogicalExpression("can_approve('alice')", engine)
        val result = expression.evaluateWithObjects(manager)

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `PrologLogicalExpression returns false for unmet conditions`() {
        val rules = """
            can_approve(User) :-
                user_role(User, 'manager'),
                user_years_of_tenure(User, Years),
                Years >= 5.
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val manager = User("alice", "manager", "engineering", 3)

        val expression = PrologLogicalExpression("can_approve('alice')", engine)
        val result = expression.evaluateWithObjects(manager)

        assertEquals(ConditionDetermination.FALSE, result)
    }

    @Test
    fun `PrologExpressionParser parses prolog prefix`() {
        val rules = """
            test_predicate(foo).
        """.trimIndent()

        val parser = PrologExpressionParser.fromRules(rules)

        val expression = parser.parse("prolog:test_predicate(foo)")
        assertNotNull(expression)

        val result = expression!!.evaluate { ConditionDetermination.UNKNOWN }
        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `PrologExpressionParser returns null for non-prolog expressions`() {
        val parser = PrologExpressionParser.fromRules("")

        val expression = parser.parse("some_condition:value")
        assertNull(expression)
    }

    @Test
    fun `PrologExpressionParser handles complex rules`() {
        val rules = """
            requires_second_approval(Expense) :-
                expense_amount(Expense, Amount),
                Amount > 10000.

            requires_second_approval(Expense) :-
                expense_category(Expense, 'capital'),
                expense_amount(Expense, Amount),
                Amount > 5000.
        """.trimIndent()

        val parser = PrologExpressionParser.fromRules(rules)
        val converter = PrologFactConverter()

        val expense = Expense("exp-001", 12000.0, "operational")
        val facts = converter.convertToFacts(expense)

        // Create a new engine with facts
        val engine = TuPrologEngine.create(rules).assertFacts(facts)
        val expression = PrologLogicalExpression("requires_second_approval('exp-001')", engine)

        val result = expression.evaluate { ConditionDetermination.UNKNOWN }
        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `PrologRuleLoader loads from string`() {
        val rules = """
            fact(a).
            fact(b).
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        assertTrue(engine.query("fact(a)"))
        assertTrue(engine.query("fact(b)"))
        assertFalse(engine.query("fact(c)"))
    }

    @Test
    fun `PrologFactConverter excludes specified fields`() {
        @PrologFact(exclude = ["permissions"])
        data class UserWithExclusion(
            val name: String,
            val role: String,
            val permissions: List<String>,
        )

        val user = UserWithExclusion("alice", "manager", listOf("read", "write"))
        val converter = PrologFactConverter()
        val facts = converter.convertToFacts(user)

        assertFalse(facts.any { it.contains("permissions") })
        assertTrue(facts.any { it.contains("name") })
        assertTrue(facts.any { it.contains("role") })
    }

    @Test
    fun `PrologFactConverter sanitizes strings with quotes`() {
        val user = User(
            name = "alice'smith",
            role = "manager",
            department = "engineering",
            yearsOfTenure = 3,
        )

        val converter = PrologFactConverter()
        val facts = converter.convertToFacts(user)

        // Should escape the quote
        assertTrue(facts.any { it.contains("alice\\'smith") })
    }

    @Test
    fun `multiple objects can be converted and queried`() {
        val rules = """
            can_approve_expense(User, Expense) :-
                user_role(User, 'manager'),
                expense_amount(Expense, Amount),
                Amount =< 5000.

            can_approve_expense(User, _) :-
                user_role(User, 'director').
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val converter = PrologFactConverter()

        val user = User("alice", "manager", "engineering", 3)
        val expense = Expense("exp-001", 4000.0, "operational")

        val userFacts = converter.convertToFacts(user)
        val expenseFacts = converter.convertToFacts(expense)

        val scopedEngine = engine
            .assertFacts(userFacts)
            .assertFacts(expenseFacts)

        assertTrue(scopedEngine.query("can_approve_expense('alice', 'exp-001')"))
    }

    @Test
    fun `PrologRuleLoader loads from class resource`() {
        val loader = PrologRuleLoader()
        val rules = loader.loadFromClassResource(
            PrologIntegrationTest::class.java,
            "test-rules.pl"
        )

        assertNotNull(rules)
        assertTrue(rules.contains("can_approve"))
        assertTrue(rules.contains("requires_second_approval"))

        val engine = TuPrologEngine.create(rules)
        val converter = PrologFactConverter()

        val admin = User("bob", "admin", "operations", 1)
        val adminFacts = converter.convertToFacts(admin)
        val scopedEngine = engine.assertFacts(adminFacts)

        assertTrue(scopedEngine.query("can_approve('bob')"))
    }

    @Test
    fun `PrologExpressionParser loads from class resource`() {
        val parser = PrologExpressionParser.fromClassResource(
            PrologIntegrationTest::class.java,
            "test-rules.pl"
        )

        val converter = PrologFactConverter()
        val manager = User("alice", "manager", "engineering", 3)
        val facts = converter.convertToFacts(manager)

        val engine = TuPrologEngine.create(
            PrologRuleLoader().loadFromClassResource(
                PrologIntegrationTest::class.java,
                "test-rules.pl"
            )
        ).assertFacts(facts)

        val expression = PrologLogicalExpression("can_approve('alice')", engine)
        val result = expression.evaluate { ConditionDetermination.UNKNOWN }

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `complex authorization scenario with multiple rules`() {
        val parser = PrologExpressionParser.fromClassResource(
            PrologIntegrationTest::class.java,
            "test-rules.pl"
        )

        val converter = PrologFactConverter()

        // Senior manager with 6 years tenure
        val seniorManager = User("carol", "manager", "engineering", 6)
        val seniorManagerFacts = converter.convertToFacts(seniorManager)

        val rules = PrologRuleLoader().loadFromClassResource(
            PrologIntegrationTest::class.java,
            "test-rules.pl"
        )
        val engine = TuPrologEngine.create(rules).assertFacts(seniorManagerFacts)

        // Should be able to approve
        assertTrue(engine.query("can_approve('carol')"))

        // Should be considered senior manager
        assertTrue(engine.query("is_senior_manager('carol')"))
    }

    @Test
    fun `security department analyst can access sensitive data`() {
        val rules = PrologRuleLoader().loadFromClassResource(
            PrologIntegrationTest::class.java,
            "test-rules.pl"
        )

        val converter = PrologFactConverter()
        val analyst = User("dave", "analyst", "security", 2)
        val analystFacts = converter.convertToFacts(analyst)

        val engine = TuPrologEngine.create(rules).assertFacts(analystFacts)

        assertTrue(engine.query("can_access_sensitive_data('dave')"))
    }

    @Test
    fun `undefined zero-arity predicates are resolved as conditions`() {
        val rules = """
            can_enter :- windowIsOpen, hasPermission.
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val expression = PrologLogicalExpression("can_enter", engine)

        // Both conditions return true
        val result1 = expression.evaluate { conditionName ->
            when (conditionName) {
                "windowIsOpen" -> ConditionDetermination.TRUE
                "hasPermission" -> ConditionDetermination.TRUE
                else -> ConditionDetermination.UNKNOWN
            }
        }
        assertEquals(ConditionDetermination.TRUE, result1)

        // One condition returns false
        val result2 = expression.evaluate { conditionName ->
            when (conditionName) {
                "windowIsOpen" -> ConditionDetermination.TRUE
                "hasPermission" -> ConditionDetermination.FALSE
                else -> ConditionDetermination.UNKNOWN
            }
        }
        assertEquals(ConditionDetermination.FALSE, result2)
    }

    @Test
    fun `unknown conditions return UNKNOWN overall`() {
        val rules = """
            can_enter :- windowIsOpen, hasPermission.
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val expression = PrologLogicalExpression("can_enter", engine)

        // One condition returns UNKNOWN
        val result = expression.evaluate { conditionName ->
            when (conditionName) {
                "windowIsOpen" -> ConditionDetermination.TRUE
                "hasPermission" -> ConditionDetermination.UNKNOWN
                else -> ConditionDetermination.UNKNOWN
            }
        }
        assertEquals(ConditionDetermination.UNKNOWN, result)
    }

    @Test
    fun `defined predicates are not treated as conditions`() {
        val rules = """
            windowIsOpen.
            can_enter :- windowIsOpen, hasPermission.
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)
        val expression = PrologLogicalExpression("can_enter", engine)

        // Only hasPermission should be checked, not windowIsOpen (already defined)
        var checkedConditions = mutableListOf<String>()
        val result = expression.evaluate { conditionName ->
            checkedConditions.add(conditionName)
            ConditionDetermination.TRUE
        }

        assertEquals(ConditionDetermination.TRUE, result)
        assertFalse(checkedConditions.contains("windowIsOpen"))
        assertTrue(checkedConditions.contains("hasPermission"))
    }

    @Test
    fun `extractZeroArityPredicates finds atoms in query`() {
        val engine = TuPrologEngine.create("")

        val predicates1 = engine.extractZeroArityPredicates("can_enter")
        assertTrue(predicates1.contains("can_enter"))

        val predicates2 = engine.extractZeroArityPredicates("windowIsOpen, hasPermission")
        assertTrue(predicates2.contains("windowIsOpen"))
        assertTrue(predicates2.contains("hasPermission"))

        // Arguments should NOT be extracted, only zero-arity predicates in goal position
        val predicates3 = engine.extractZeroArityPredicates("can_approve('alice')")
        assertFalse(predicates3.contains("can_approve")) // has arguments, not zero-arity
        assertFalse(predicates3.contains("alice")) // in argument position

        val predicates4 = engine.extractZeroArityPredicates("can_enter, can_approve('alice')")
        assertTrue(predicates4.contains("can_enter")) // zero-arity in goal position
        assertFalse(predicates4.contains("alice")) // in argument position
    }

    @Test
    fun `isPredicateDefined checks both rules and facts`() {
        val rules = """
            parent(tom, bob).
            grandparent(X, Y) :- parent(X, Z), parent(Z, Y).
        """.trimIndent()

        val engine = TuPrologEngine.create(rules)

        assertTrue(engine.isPredicateDefined("parent"))
        assertTrue(engine.isPredicateDefined("grandparent"))
        assertFalse(engine.isPredicateDefined("sibling"))

        val engineWithFact = engine.assertFact("sibling(bob, liz)")
        assertTrue(engineWithFact.isPredicateDefined("sibling"))
    }
}
