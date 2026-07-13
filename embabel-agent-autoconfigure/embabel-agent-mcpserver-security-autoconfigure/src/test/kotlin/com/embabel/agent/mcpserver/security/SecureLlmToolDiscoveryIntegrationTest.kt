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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.config.mcpserver.security.SecureAgentToolConfiguration
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
 * Proves that a bean carrying both [SecureAgentTool] and [LlmTool] on the same method survives
 * CGLIB proxying end to end: the tool is still discoverable via [Tool.safelyFromInstance] after
 * proxying, and the security aspect still enforces access when the discovered tool is called.
 *
 * This is the sharpest edge from GitHub issue #1779: discovery (`embabel-agent-api`) and
 * enforcement (`SecureAgentToolAspect`, this module) are two independent mechanisms that both
 * have to see through the same CGLIB proxy. Either one regressing on its own would be silent
 * unless something exercises them together, which is what this test does.
 */
@SpringJUnitConfig
@DisplayName("SecureAgentTool + LlmTool discovery - Integration")
class SecureLlmToolDiscoveryIntegrationTest {

    @Autowired
    lateinit var agent: SecuredToolAgent

    @Configuration
    @Import(SecureAgentToolConfiguration::class)
    @EnableAspectJAutoProxy
    open class TestConfig {

        @Bean
        open fun securedToolAgent(): SecuredToolAgent = SecuredToolAgent()
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

    private fun discoveredTool(name: String): Tool =
        Tool.safelyFromInstance(agent).first { it.definition.name == name }

    @Test
    fun `agent bean is a Spring AOP proxy, not a plain instance`() {
        val targetClass = AopProxyUtils.ultimateTargetClass(agent)
        Assertions.assertThat(agent.javaClass).isNotEqualTo(targetClass)
    }

    @Test
    fun `secured_greet is discovered as a tool after proxying`() {
        val tools = Tool.safelyFromInstance(agent)
        Assertions.assertThat(tools.map { it.definition.name }).contains("secured_greet")
    }

    @Test
    fun `unauthenticated invocation does not return the success payload and surfaces AccessDeniedException`() {
        val tool = discoveredTool("secured_greet")

        // The aspect's AccessDeniedException may come out as a thrown exception or as a
        // returned Tool.Result.Error, depending on how far the invocation unwinds before the
        // tool wrapper's own try/catch gets it. Either is an acceptable denial shape here.
        val outcome: Any = try {
            tool.call("""{"name": "World"}""")
        } catch (e: Throwable) {
            e
        }

        Assertions.assertThat(outcome).isNotInstanceOf(Tool.Result.Text::class.java)

        val deniedSomewhereInChain = when (outcome) {
            is Throwable -> generateSequence(outcome) { it.cause }.any { it is AccessDeniedException }
            is Tool.Result.Error -> generateSequence(outcome.cause as Throwable?) { it.cause }
                .any { it is AccessDeniedException } ||
                outcome.message.contains("AccessDeniedException") ||
                outcome.message.contains("Access is denied")

            else -> false
        }
        Assertions.assertThat(deniedSomewhereInChain)
            .withFailMessage("expected AccessDeniedException somewhere in the denial, got: %s", outcome)
            .isTrue()
    }

    @Test
    fun `authorized invocation returns the tool's real result`() {
        authenticateWith("tools:use")
        val tool = discoveredTool("secured_greet")

        val result = tool.call("""{"name": "World"}""")

        Assertions.assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
        Assertions.assertThat((result as Tool.Result.Text).content).contains("Hello")
    }
}

/**
 * Test fixture where one method carries both defense-in-depth annotations: [SecureAgentTool]
 * guards it via the aspect, [LlmTool] makes it discoverable as a tool. Must be `open` for CGLIB
 * subclassing.
 */
open class SecuredToolAgent {

    @SecureAgentTool("hasAuthority('tools:use')")
    @LlmTool(name = "secured_greet", description = "Greets, if you're allowed")
    open fun securedGreet(name: String): String = "Hello, $name"
}
