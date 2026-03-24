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

import com.embabel.agent.config.mcpserver.security.SecureAgentToolConfiguration
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.*
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

/**
 * Integration tests for [SecureAgentToolAspect].
 *
 * Unlike `SecureAgentToolAspectTest` (which bypasses AspectJ weaving via reflection),
 * these tests load a real Spring context with `@EnableAspectJAutoProxy` and verify
 * that the aspect actually intercepts calls on a CGLIB-proxied bean.
 *
 * > **Note:** This is the test that catches the most common misconfiguration: the aspect
 * > beans exist but `@EnableAspectJAutoProxy` is missing, so the `@Around` advice never fires.
 *
 * ### What is verified
 *
 * - Aspect fires on a real Spring CGLIB proxy (not a plain instance)
 * - `@EnableAspectJAutoProxy` is in effect via [SecureAgentToolConfiguration]
 * - SpEL expressions are evaluated against the live [SecurityContextHolder][org.springframework.security.core.context.SecurityContextHolder]
 * - Unannotated methods are **not** intercepted
 * - [AccessDeniedException][org.springframework.security.access.AccessDeniedException] propagates correctly through the proxy
 *
 * @see SecureAgentToolAspect
 * @see SecureAgentToolConfiguration
 */
@SpringJUnitConfig
@DisplayName("SecureAgentToolAspect - Integration")
class SecureAgentToolAspectIntegrationTest {

    // Loaded from TestConfig - will be a CGLIB proxy, not a plain ProxiedTestAgent instance
    @Autowired
    lateinit var agent: ProxiedTestAgent

    @Configuration
    @Import(SecureAgentToolConfiguration::class)
    @EnableAspectJAutoProxy
    open class TestConfig {

        @Bean
        open fun proxiedTestAgent(): ProxiedTestAgent = ProxiedTestAgent()
    }

    @BeforeEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun resetContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateWith(vararg authorities: String) {
        val auth = TestingAuthenticationToken(
            "integration-user",
            "credentials",
            authorities.map { SimpleGrantedAuthority(it) },
        )
        auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
    }

    @Test
    fun `agent bean is a Spring AOP proxy, not a plain instance`() {
        // Confirms CGLIB proxying is active - if this fails, no advice will fire
        val targetClass = AopProxyUtils.ultimateTargetClass(agent)
        Assertions.assertThat(agent.javaClass).isNotEqualTo(targetClass)
    }

    @Nested
    @DisplayName("Aspect fires on Spring proxy")
    inner class AspectFiringOnProxy {

        @Test
        fun `grants access when principal has required authority`() {
            authenticateWith("payments:write")
            Assertions.assertThat(agent.processPayment()).isEqualTo("payment-processed")
        }

        @Test
        fun `denies access when principal has wrong authority`() {
            authenticateWith("payments:read")
            Assertions.assertThatThrownBy { agent.processPayment() }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessageContaining("processPayment")
                .hasMessageContaining("integration-user")
        }

        @Test
        fun `denies access when SecurityContext is empty`() {
            // No authentication set at all
            Assertions.assertThatThrownBy { agent.processPayment() }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessageContaining("No Authentication present")
        }
    }

    @Nested
    @DisplayName("hasAnyAuthority on proxy")
    inner class HasAnyAuthorityOnProxy {

        @Test
        fun `grants access with first matching authority`() {
            authenticateWith("finance:read")
            Assertions.assertThat(agent.getBalance()).isEqualTo("balance-result")
        }

        @Test
        fun `grants access with second matching authority`() {
            authenticateWith("finance:admin")
            Assertions.assertThat(agent.getBalance()).isEqualTo("balance-result")
        }

        @Test
        fun `denies access with unrelated authority`() {
            authenticateWith("payments:write")
            Assertions.assertThatThrownBy { agent.getBalance() }
                .isInstanceOf(AccessDeniedException::class.java)
        }
    }

    @Nested
    @DisplayName("hasRole on proxy")
    inner class HasRoleOnProxy {

        @Test
        fun `grants access when principal carries ROLE_ prefixed authority`() {
            authenticateWith("ROLE_ADMIN")
            Assertions.assertThat(agent.adminOperation()).isEqualTo("admin-result")
        }

        @Test
        fun `denies access when ROLE_ prefix is missing`() {
            authenticateWith("ADMIN")
            Assertions.assertThatThrownBy { agent.adminOperation() }
                .isInstanceOf(AccessDeniedException::class.java)
        }
    }

    @Nested
    @DisplayName("Unannotated methods on proxy")
    inner class UnannotatedOnProxy {

        @Test
        fun `passes through with authentication present`() {
            authenticateWith("any:authority")
            Assertions.assertThat(agent.unprotectedOperation()).isEqualTo("unprotected-result")
        }

        @Test
        fun `passes through with no authentication - aspect must not fire`() {
            // Critical: if the aspect incorrectly intercepts unannotated methods
            // this call would throw AccessDeniedException
            Assertions.assertThat(agent.unprotectedOperation()).isEqualTo("unprotected-result")
        }
    }

    @Nested
    @DisplayName("Multiple authorities on principal")
    inner class MultipleAuthorities {

        @Test
        fun `grants access when one of multiple carried authorities matches`() {
            authenticateWith("payments:read", "payments:write", "finance:read")
            Assertions.assertThat(agent.processPayment()).isEqualTo("payment-processed")
        }

        @Test
        fun `denies access when none of multiple carried authorities match`() {
            authenticateWith("reporting:read", "audit:read")
            Assertions.assertThatThrownBy { agent.processPayment() }
                .isInstanceOf(AccessDeniedException::class.java)
        }
    }
}

/**
 * Test fixture representing a CGLIB-proxiable agent bean.
 *
 * Must be `open` (not `final`) for CGLIB subclassing. Kotlin classes are `final` by default —
 * the `kotlin-allopen` plugin handles Spring stereotype annotations (`@Component`, `@Service`,
 * etc.) but plain test fixtures must be declared `open` explicitly.
 *
 * Each method exercises a different [SecureAgentTool] SpEL variant to keep
 * the integration assertions focused and independent.
 */
open class ProxiedTestAgent {

    @SecureAgentTool("hasAuthority('payments:write')")
    open fun processPayment(): String = "payment-processed"

    @SecureAgentTool("hasAnyAuthority('finance:read', 'finance:admin')")
    open fun getBalance(): String = "balance-result"

    @SecureAgentTool("hasRole('ADMIN')")
    open fun adminOperation(): String = "admin-result"

    open fun unprotectedOperation(): String = "unprotected-result"
}
