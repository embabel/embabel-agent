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
import org.springframework.util.ClassUtils
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Method

/**
 * AOP aspect that enforces [SecureAgentTool] security expressions on Embabel agent action methods.
 *
 * When an [@Action] method annotated with [SecureAgentTool] is invoked by Embabel's
 * [DefaultActionMethodManager], this aspect intercepts the call and evaluates the SpEL
 * expression in [SecureAgentTool.value] against the current
 * [Authentication][org.springframework.security.core.Authentication] using Spring Security's
 * [MethodSecurityExpressionHandler][org.springframework.security.access.expression.method.MethodSecurityExpressionHandler]
 * — the same engine that powers [@PreAuthorize][org.springframework.security.access.prepost.PreAuthorize].
 *
 * Invocation proceeds only if the expression evaluates to `true`. Otherwise an
 * [AccessDeniedException][org.springframework.security.access.AccessDeniedException] is thrown,
 * resulting in a `403` at the MCP transport layer.
 *
 * ### Invocation order
 *
 * ```
 * MCP Client request
 *   → Spring Security FilterChain       (transport-level, rejects unauthenticated)
 *   → Embabel GOAP planner              (selects goal/action)
 *   → DefaultActionMethodManager        (resolves and invokes the @Action method)
 *   → SecureAgentToolAspect             (evaluates @SecureAgentTool SpEL — this class)
 *   → @Action method body               (executes only if SpEL passes)
 * ```
 *
 * ### Thread safety
 *
 * This aspect is stateless. [SecurityContextHolder][org.springframework.security.core.context.SecurityContextHolder]
 * provides per-request authentication via its default [ThreadLocal] strategy,
 * so concurrent invocations are isolated.
 *
 * @see SecureAgentTool
 * @see DefaultActionMethodManager
 * @see org.springframework.security.access.prepost.PreAuthorize
 */
@Aspect
class SecureAgentToolAspect(
    private val expressionHandler: MethodSecurityExpressionHandler,
) {

    private val logger = LoggerFactory.getLogger(SecureAgentToolAspect::class.java)

    /**
     * Intercepts methods annotated with [SecureAgentTool] at method or class level,
     * enforcing the declared SpEL expression before the action body executes.
     *
     * Method-level annotation takes precedence over class-level. `@within` matches
     * all methods in a class carrying the annotation.
     *
     * @throws [AccessDeniedException][org.springframework.security.access.AccessDeniedException]
     * if the expression evaluates to `false` or no [Authentication][org.springframework.security.core.Authentication]
     * is present in the [SecurityContextHolder][org.springframework.security.core.context.SecurityContextHolder].
     */
    @Around(
        "@annotation(com.embabel.agent.mcpserver.security.SecureAgentTool) || " +
                "@within(com.embabel.agent.mcpserver.security.SecureAgentTool)"
    )
    fun enforceAgentToolSecurity(joinPoint: ProceedingJoinPoint): Any? {
        val method = (joinPoint.signature as MethodSignature).method
        val target = joinPoint.target
        // Unwrap CGLIB proxy to read annotations from the actual class
        val targetClass = ClassUtils.getUserClass(target.javaClass)

        // Method-level annotation takes precedence over class-level
        val secureAgentTool = method.getAnnotation(SecureAgentTool::class.java)
            ?: targetClass.getAnnotation(SecureAgentTool::class.java)
            ?: return joinPoint.proceed()

        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw AccessDeniedException(
                "No Authentication present in SecurityContext. " +
                        "Ensure Spring Security is configured and a Bearer token is supplied by the MCP client.",
            )

        logger.debug(
            "Evaluating @SecureAgentTool expression [{}] for principal '{}' on {}.{}",
            secureAgentTool.value,
            authentication.name,
            targetClass.simpleName,
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
            val message = "Agent tool '${method.name}' on '${targetClass.simpleName}' " +
                    "denied for principal '${authentication.name}' " +
                    "- expression: [${secureAgentTool.value}]"
            logger.warn(message)
            throw AccessDeniedException(message)
        }

        logger.debug(
            "Access granted for principal '{}' on {}.{}",
            authentication.name,
            targetClass.simpleName,
            method.name,
        )

        return joinPoint.proceed()
    }

    /**
     * Evaluates a Spring Security SpEL expression using [MethodSecurityExpressionHandler][org.springframework.security.access.expression.method.MethodSecurityExpressionHandler].
     *
     * Builds a [MethodInvocation][org.aopalliance.intercept.MethodInvocation] adapter so the
     * expression handler can bind method parameters (e.g. `#request`) and the authentication
     * root object, exactly as [@PreAuthorize][org.springframework.security.access.prepost.PreAuthorize]
     * does internally.
     *
     * > **Note:** `getStaticPart()` returns [AccessibleObject] to satisfy the
     * > [Joinpoint][org.aopalliance.intercept.Joinpoint] contract. [Method] is a subclass
     * > of [AccessibleObject], so returning the method reference is valid.
     *
     * @param expression the SpEL expression string from [SecureAgentTool.value]
     * @param authentication the current [Authentication][org.springframework.security.core.Authentication]
     * from [SecurityContextHolder][org.springframework.security.core.context.SecurityContextHolder]
     * @param method the intercepted [Method]
     * @param target the bean instance on which the method is being invoked
     * @param args the runtime arguments passed to the intercepted method
     * @return `true` if the expression grants access, `false` otherwise
     */
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
