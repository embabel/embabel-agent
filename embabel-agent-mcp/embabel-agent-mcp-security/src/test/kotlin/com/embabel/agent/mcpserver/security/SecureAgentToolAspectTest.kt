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
package com.embabel.agent.mcpserver.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Unit tests for [SecureAgentToolAspect].
 *
 * Tests exercise the SpEL evaluation logic directly against a real
 * [DefaultMethodSecurityExpressionHandler][org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler],
 * without requiring a full Spring context. The aspect is constructed manually and invoked
 * via [SecureAgentToolAspectInvoker], a test-only bridge that mirrors what the aspect does
 * internally without needing AspectJ proxy weaving.
 *
 * > **Note:** The following are intentionally **not** tested here — they are covered by
 * > [SecureAgentToolAspectIntegrationTest]:
 * > - That the aspect actually intercepts calls on a Spring-managed proxy
 * > - That `@EnableAspectJAutoProxy` wires the aspect correctly
 *
 * @see SecureAgentToolAspect
 * @see SecureAgentToolAspectIntegrationTest
 */
@DisplayName("SecureAgentToolAspect")
class SecureAgentToolAspectTest {

    private val expressionHandler = DefaultMethodSecurityExpressionHandler()
    private val aspect = SecureAgentToolAspect(expressionHandler)

    /**
     * Minimal target exposing a representative set of [SecureAgentTool] SpEL expressions,
     * one per supported built-in (`hasAuthority`, `hasAnyAuthority`, `hasRole`, `isAuthenticated`),
     * plus one unannotated method to verify the aspect does not interfere with unprotected calls.
     */
    @Suppress("unused")
    inner class TestAgent {
        @SecureAgentTool("hasAuthority('payments:write')")
        fun processPayment(): String = "payment-processed"

        @SecureAgentTool("hasAnyAuthority('finance:read', 'finance:admin')")
        fun getBalance(): String = "balance-result"

        @SecureAgentTool("hasRole('ADMIN')")
        fun adminOperation(): String = "admin-result"

        @SecureAgentTool("isAuthenticated()")
        fun authenticatedOperation(): String = "authenticated-result"

        /** No annotation — the aspect must not interfere with this method. */
        fun unprotectedOperation(): String = "unprotected-result"
    }

    private val target = TestAgent()

    @BeforeEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun resetSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateWith(vararg authorities: String) {
        val auth = TestingAuthenticationToken(
            "test-user",
            "credentials",
            authorities.map { SimpleGrantedAuthority(it) },
        )
        auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun invokeAspect(methodName: String, vararg args: Any?): Any? =
        SecureAgentToolAspectInvoker(aspect, target).invoke(methodName, *args)

    @Nested
    @DisplayName("When SecurityContext has no Authentication")
    inner class NoAuthentication {

        @Test
        fun `throws AccessDeniedException for SecureAgentTool method`() {
            assertThatThrownBy { invokeAspect("processPayment") }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessageContaining("No Authentication present")
        }

        @Test
        fun `does not throw for unannotated method`() {
            // Aspect must be completely opt-in - no annotation, no interception
            val result = invokeAspect("unprotectedOperation")
            assertThat(result).isEqualTo("unprotected-result")
        }
    }

    @Nested
    @DisplayName("hasAuthority expression")
    inner class HasAuthority {

        @Test
        fun `grants access when principal has required authority`() {
            authenticateWith("payments:write")
            assertThat(invokeAspect("processPayment")).isEqualTo("payment-processed")
        }

        @Test
        fun `denies access when principal has wrong authority`() {
            authenticateWith("payments:read")
            assertThatThrownBy { invokeAspect("processPayment") }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessageContaining("processPayment")
                .hasMessageContaining("test-user")
        }

        @Test
        fun `denies access when principal has no authorities`() {
            authenticateWith()
            assertThatThrownBy { invokeAspect("processPayment") }
                .isInstanceOf(AccessDeniedException::class.java)
        }

        @Test
        fun `denied message includes expression for diagnostics`() {
            authenticateWith("payments:read")
            assertThatThrownBy { invokeAspect("processPayment") }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessageContaining("payments:write")
        }
    }

    @Nested
    @DisplayName("hasAnyAuthority expression")
    inner class HasAnyAuthority {

        @Test
        fun `grants access when principal has first listed authority`() {
            authenticateWith("finance:read")
            assertThat(invokeAspect("getBalance")).isEqualTo("balance-result")
        }

        @Test
        fun `grants access when principal has second listed authority`() {
            authenticateWith("finance:admin")
            assertThat(invokeAspect("getBalance")).isEqualTo("balance-result")
        }

        @Test
        fun `grants access when principal has both listed authorities`() {
            authenticateWith("finance:read", "finance:admin")
            assertThat(invokeAspect("getBalance")).isEqualTo("balance-result")
        }

        @Test
        fun `denies access when principal has unrelated authority`() {
            authenticateWith("payments:write")
            assertThatThrownBy { invokeAspect("getBalance") }
                .isInstanceOf(AccessDeniedException::class.java)
        }
    }

    @Nested
    @DisplayName("hasRole expression")
    inner class HasRole {

        @Test
        fun `grants access when principal has ROLE_ prefixed authority`() {
            // hasRole() prepends ROLE_ automatically - principal must carry ROLE_ADMIN
            authenticateWith("ROLE_ADMIN")
            assertThat(invokeAspect("adminOperation")).isEqualTo("admin-result")
        }

        @Test
        fun `denies access when principal carries authority without ROLE_ prefix`() {
            authenticateWith("ADMIN")
            assertThatThrownBy { invokeAspect("adminOperation") }
                .isInstanceOf(AccessDeniedException::class.java)
        }
    }

    @Nested
    @DisplayName("isAuthenticated expression")
    inner class IsAuthenticated {

        @Test
        fun `grants access for any authenticated principal regardless of authorities`() {
            authenticateWith("any:authority")
            assertThat(invokeAspect("authenticatedOperation")).isEqualTo("authenticated-result")
        }

        @Test
        fun `grants access for authenticated principal with no authorities`() {
            authenticateWith()
            assertThat(invokeAspect("authenticatedOperation")).isEqualTo("authenticated-result")
        }

        @Test
        fun `denies access when SecurityContext has no Authentication`() {
            assertThatThrownBy { invokeAspect("authenticatedOperation") }
                .isInstanceOf(AccessDeniedException::class.java)
        }
    }

    @Nested
    @DisplayName("Unannotated methods")
    inner class UnannotatedMethods {

        @Test
        fun `passes through with correct authority present`() {
            authenticateWith("payments:write")
            assertThat(invokeAspect("unprotectedOperation")).isEqualTo("unprotected-result")
        }

        @Test
        fun `passes through even with no authentication`() {
            // No auth set - aspect must not block unannotated methods
            assertThat(invokeAspect("unprotectedOperation")).isEqualTo("unprotected-result")
        }
    }

    @Nested
    @DisplayName("Class-level annotation")
    inner class ClassLevelAnnotation {

        /**
         * Agent secured entirely at the class level — all methods inherit the
         * `agent:read` constraint without needing individual [SecureAgentTool] annotations.
         */
        @SecureAgentTool("hasAuthority('agent:read')")
        @Suppress("unused")
        inner class FullySecuredAgent {
            fun step1(): String = "step1-result"
            fun step2(): String = "step2-result"
            fun goalAction(): String = "goal-result"
        }

        /**
         * Agent with a class-level default overridden on a single privileged method.
         * Verifies that method-level [SecureAgentTool] takes precedence over the class-level one.
         */
        @SecureAgentTool("hasAuthority('agent:read')")
        @Suppress("unused")
        inner class AgentWithMethodOverride {
            fun regularStep(): String = "regular-result"

            @SecureAgentTool("hasAuthority('agent:admin')")
            fun privilegedStep(): String = "privileged-result"
        }

        private fun invokeOn(instance: Any, methodName: String): Any? =
            SecureAgentToolAspectInvoker(aspect, instance).invoke(methodName)

        @Test
        fun `all methods in class-annotated agent are protected`() {
            val agent = FullySecuredAgent()
            // None of the methods pass without auth
            assertThatThrownBy { invokeOn(agent, "step1") }
                .isInstanceOf(AccessDeniedException::class.java)
            assertThatThrownBy { invokeOn(agent, "step2") }
                .isInstanceOf(AccessDeniedException::class.java)
            assertThatThrownBy { invokeOn(agent, "goalAction") }
                .isInstanceOf(AccessDeniedException::class.java)
        }

        @Test
        fun `all methods pass when principal has class-level authority`() {
            authenticateWith("agent:read")
            val agent = FullySecuredAgent()
            assertThat(invokeOn(agent, "step1")).isEqualTo("step1-result")
            assertThat(invokeOn(agent, "step2")).isEqualTo("step2-result")
            assertThat(invokeOn(agent, "goalAction")).isEqualTo("goal-result")
        }

        @Test
        fun `method-level annotation takes precedence over class-level`() {
            val agent = AgentWithMethodOverride()
            // agent:read satisfies class-level but NOT the method-level override
            authenticateWith("agent:read")
            assertThat(invokeOn(agent, "regularStep")).isEqualTo("regular-result")
            assertThatThrownBy { invokeOn(agent, "privilegedStep") }
                .isInstanceOf(AccessDeniedException::class.java)
        }

        @Test
        fun `method-level override is satisfied by its own authority`() {
            val agent = AgentWithMethodOverride()
            authenticateWith("agent:read", "agent:admin")
            assertThat(invokeOn(agent, "regularStep")).isEqualTo("regular-result")
            assertThat(invokeOn(agent, "privilegedStep")).isEqualTo("privileged-result")
        }
    }
}

/**
 * Test-only bridge that mirrors [SecureAgentToolAspect]'s logic without AspectJ proxy weaving.
 *
 * Reflectively calls the private `evaluateExpression` method on [SecureAgentToolAspect],
 * then invokes the target method directly if access is granted. Annotation resolution
 * mirrors the aspect: method-level [SecureAgentTool] takes precedence over class-level.
 *
 * > **Note:** This invoker exercises SpEL evaluation in isolation. Proxy interception
 * > is verified separately by [SecureAgentToolAspectIntegrationTest].
 *
 * @see SecureAgentToolAspectIntegrationTest
 */
private class SecureAgentToolAspectInvoker(
    private val aspect: SecureAgentToolAspect,
    private val target: Any,
) {
    fun invoke(methodName: String, vararg args: Any?): Any? {
        val method = target::class.java.declaredMethods.first { it.name == methodName }
        // Mirror aspect resolution: method-level takes precedence over class-level
        val annotation = method.getAnnotation(SecureAgentTool::class.java)
            ?: target::class.java.getAnnotation(SecureAgentTool::class.java)

        val authentication = SecurityContextHolder.getContext().authentication

        if (annotation != null) {
            // Unannotated path: no auth check at all
            if (authentication == null) {
                throw AccessDeniedException(
                    "No Authentication present in SecurityContext. " +
                            "Ensure Spring Security is configured and a Bearer token is supplied by the MCP client.",
                )
            }

            val evaluateMethod = SecureAgentToolAspect::class.java.getDeclaredMethod(
                "evaluateExpression",
                String::class.java,
                org.springframework.security.core.Authentication::class.java,
                java.lang.reflect.Method::class.java,
                Any::class.java,
                Array<Any?>::class.java,
            )
            evaluateMethod.isAccessible = true

            val granted = evaluateMethod.invoke(
                aspect,
                annotation.value,
                authentication,
                method,
                target,
                args,
            ) as Boolean

            if (!granted) {
                throw AccessDeniedException(
                    "Agent tool '${method.name}' on '${target::class.simpleName}' " +
                            "denied for principal '${authentication.name}' " +
                            "- expression: [${annotation.value}]",
                )
            }
        }

        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
