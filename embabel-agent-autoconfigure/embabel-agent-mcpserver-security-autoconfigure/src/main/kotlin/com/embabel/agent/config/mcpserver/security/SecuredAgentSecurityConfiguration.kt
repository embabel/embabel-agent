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
package com.embabel.agent.config.mcpserver.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Security configuration for MCP endpoints, active only when the `secured` Spring profile is set.
 *
 * Requires a valid JWT bearer token on all requests to `/sse/`, `/mcp/`, and `/message/`.
 * Sessions are stateless — no HTTP session is created or consulted between calls.
 *
 * Per-tool authority checks are enforced via `@SecureAgentTool` on `@AchievesGoal` actions.
 *
 * JWT authorities are read from the `authorities` claim with no prefix applied, so a claim
 * value such as `"TOOL_search"` maps directly to the Spring Security granted authority
 * `"TOOL_search"`.
 */
@Configuration
@EnableWebSecurity
class SecuredAgentSecurityConfiguration {

    /**
     * Registers a [SecurityFilterChain] that protects all MCP-related endpoints.
     *
     * - All requests to `/sse/`, `/mcp/`, and `/message/` must be authenticated.
     * - Session creation policy is [SessionCreationPolicy.STATELESS].
     * - OAuth2 resource server JWT validation delegates to [jwtAuthenticationConverter].
     * - CSRF protection is disabled (appropriate for stateless, token-based APIs).
     *
     * @param http the [HttpSecurity] builder to configure
     * @return the built [SecurityFilterChain]
     */
    @Bean
    fun securedFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/sse/**", "/mcp/**", "/message/**")
            .authorizeHttpRequests { auth ->
                auth.anyRequest().authenticated()
            }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }
            .csrf { it.disable() }
        return http.build()
    }

    /**
     * Produces a [JwtAuthenticationConverter] that extracts granted authorities from the
     * `authorities` claim in the JWT payload.
     *
     * No authority prefix is applied, so claim values map directly to Spring Security
     * granted authorities without modification.
     *
     * @return the configured [JwtAuthenticationConverter]
     */
    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val authoritiesConverter = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("authorities")
            setAuthorityPrefix("")
        }
        return JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(authoritiesConverter)
        }
    }
}
