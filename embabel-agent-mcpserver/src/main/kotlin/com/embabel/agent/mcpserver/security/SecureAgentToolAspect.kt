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

import org.aopalliance.intercept.MethodInvocation
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Method

// AOP aspect that enforces SecureAgentTool security expressions on Embabel agent action methods.
//
// When an @Action method annotated with @SecureAgentTool is invoked by Embabel's
// DefaultActionMethodManager, this aspect intercepts the call and evaluates the SpEL
// expression in SecureAgentTool.value against the current Authentication using Spring
// Security's MethodSecurityExpressionHandler, the same engine that powers @PreAuthorize.
//
// Invocation proceeds only if the expression evaluates to true.
// Otherwise an AccessDeniedException is thrown, resulting in a 403 at the MCP transport layer.
//
// Invocation order:
//   MCP Client request
//   -> Spring Security FilterChain  (transport-level, rejects unauthenticated)
//   -> Embabel GOAP planner         (selects goal/action)
//   -> DefaultActionMethodManager   (resolves and invokes the @Action method)
//   -> SecureAgentToolAspect        (evaluates @SecureAgentTool SpEL, this class)
//   -> @Action method body          (executes only if SpEL passes)
//
// This aspect is stateless. SecurityContextHolder provides per-request authentication
// in the default ThreadLocal strategy, so concurrent invocations are isolated.
@Aspect
class SecureAgentToolAspect(
    private val expressionHandler: MethodSecurityExpressionHandler,
) {

    private val logger = LoggerFactory.getLogger(SecureAgentToolAspect::class.java)

    // Intercepts any method annotated with @SecureAgentTool and evaluates the declared
    // SpEL expression before allowing the invocation to proceed.
    // Throws AccessDeniedException if the expression evaluates to false or
    // no Authentication is present in the SecurityContextHolder.
    @Around("@annotation(secureAgentTool)")
    fun enforceAgentToolSecurity(
        joinPoint: ProceedingJoinPoint,
        secureAgentTool: SecureAgentTool,
    ): Any? {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw AccessDeniedException(
                "No Authentication present in SecurityContext. " +
                    "Ensure Spring Security is configured and a Bearer token is supplied by the MCP client.",
            )

        val method = (joinPoint.signature as MethodSignature).method
        val target = joinPoint.target

        logger.debug(
            "Evaluating @SecureAgentTool expression [{}] for principal '{}' on {}.{}",
            secureAgentTool.value,
            authentication.name,
            target::class.simpleName,
            method.name,
        )

        val granted = evaluateExpression(
            expression = secureAgentTool.value,
            authentication = authentication,
            method = method,
            target = target,
            args = joinPoint.args,
        )

        if (!granted) {
            val message = "Agent tool '${method.name}' on '${target::class.simpleName}' " +
                "denied for principal '${authentication.name}' " +
                "- expression: [${secureAgentTool.value}]"
            logger.warn(message)
            throw AccessDeniedException(message)
        }

        logger.debug(
            "Access granted for principal '{}' on {}.{}",
            authentication.name,
            target::class.simpleName,
            method.name,
        )

        return joinPoint.proceed()
    }

    // Evaluates a Spring Security SpEL expression using MethodSecurityExpressionHandler.
    //
    // Builds a MethodInvocation adapter so the expression handler can bind method
    // parameters (e.g. #request) and the authentication root object, exactly as
    // @PreAuthorize does internally.
    //
    // getStaticPart() must return AccessibleObject to satisfy the Joinpoint contract.
    // Method is a subclass of AccessibleObject so returning the method reference is valid.
    private fun evaluateExpression(
        expression: String,
        authentication: Authentication,
        method: Method,
        target: Any,
        args: Array<Any?>,
    ): Boolean {
        val methodInvocation = object : MethodInvocation {
            override fun getMethod(): Method = method
            override fun getArguments(): Array<Any?> = args
            override fun getThis(): Any = target
            override fun getStaticPart(): AccessibleObject = method
            override fun proceed(): Any? =
                throw UnsupportedOperationException("proceed() must not be called on the security adapter")
        }

        val evaluationContext = expressionHandler.createEvaluationContext(authentication, methodInvocation)
        val parsedExpression = expressionHandler.expressionParser.parseExpression(expression)

        return parsedExpression.getValue(evaluationContext, Boolean::class.java) ?: false
    }
}
