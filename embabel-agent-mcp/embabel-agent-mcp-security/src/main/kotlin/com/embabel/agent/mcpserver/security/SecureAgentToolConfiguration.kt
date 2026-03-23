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

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

/**
 * Spring configuration that wires [SecureAgentToolAspect] into the application context.
 *
 * Enables Spring method security via
 * [@EnableMethodSecurity][org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity],
 * making a [MethodSecurityExpressionHandler][org.springframework.security.access.expression.method.MethodSecurityExpressionHandler]
 * available for [SecureAgentToolAspect] to evaluate SpEL expressions using the same engine
 * that powers [@PreAuthorize][org.springframework.security.access.prepost.PreAuthorize].
 *
 * Declares [SecureAgentToolAspect] as an explicit Spring bean rather than relying on
 * `@Component` scanning, keeping lifecycle predictable when used as a library dependency.
 *
 * ### Usage
 *
 * Import this configuration into your application:
 *
 * ```kotlin
 * @SpringBootApplication
 * @Import(SecureAgentToolConfiguration::class)
 * class MyAgentApplication
 * ```
 *
 * > **Note:** This configuration handles method-level security only. To protect the MCP SSE
 * > endpoint at the HTTP transport layer, add an `HttpSecurity` configuration that secures
 * > the `/sse` and `/mcp` paths with an OAuth2 resource server or equivalent.
 *
 * @see SecureAgentToolAspect
 * @see SecureAgentTool
 */
@Configuration
@EnableMethodSecurity
class SecureAgentToolConfiguration {

    /**
     * Provides the [MethodSecurityExpressionHandler][org.springframework.security.access.expression.method.MethodSecurityExpressionHandler]
     * used by [SecureAgentToolAspect] to evaluate [SecureAgentTool] SpEL expressions.
     *
     * [DefaultMethodSecurityExpressionHandler][org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler]
     * supports the full Spring Security SpEL vocabulary: `hasAuthority`, `hasRole`,
     * `hasAnyAuthority`, bean references, parameter binding via `#paramName`, and more.
     *
     * > **Note:** If the application declares a custom [MethodSecurityExpressionHandler][org.springframework.security.access.expression.method.MethodSecurityExpressionHandler]
     * > bean, Spring will inject that one instead, so `hasPermission` expressions work transparently.
     */
    @Bean
    fun methodSecurityExpressionHandler(): MethodSecurityExpressionHandler =
        DefaultMethodSecurityExpressionHandler()

    /**
     * Registers [SecureAgentToolAspect] as a Spring-managed bean.
     *
     * Declared explicitly here rather than via `@Component` on the aspect class to avoid
     * auto-scan surprises when the module is used as a library without full classpath scanning.
     *
     * @param expressionHandler the [MethodSecurityExpressionHandler][org.springframework.security.access.expression.method.MethodSecurityExpressionHandler]
     * used to evaluate SpEL expressions declared in [SecureAgentTool.value]
     */
    @Bean
    fun secureAgentToolAspect(
        expressionHandler: MethodSecurityExpressionHandler,
    ): SecureAgentToolAspect = SecureAgentToolAspect(expressionHandler)
}
