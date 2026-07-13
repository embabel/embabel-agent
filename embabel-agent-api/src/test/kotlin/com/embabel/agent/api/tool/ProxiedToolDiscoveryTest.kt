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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.LlmTool
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory

/**
 * Covers issue #1779: tools on Spring-proxied beans must still be discoverable.
 *
 * A CGLIB proxy (class-based, `isProxyTargetClass = true`) subclasses the target,
 * so its own class never carries the `@LlmTool` annotations -- they live on the
 * original target class. Discovery has to reflect on that target class, but
 * invocation still has to go through the proxy instance so AOP advice actually
 * runs. A JDK dynamic proxy (interface-based) can't do that at all: a method
 * pulled off the target class can't be invoked on an interface proxy, so those
 * stay undiscovered, just with a WARN instead of a silent DEBUG.
 */
class ProxiedToolDiscoveryTest {

    open class GreetingTools {
        @LlmTool(name = "greet", description = "Greets someone")
        open fun greet(name: String): String = "Hello, $name"
    }

    open class NoToolsHere {
        fun notATool(): String = "nothing to see here"
    }

    interface Greeter {
        fun greet(name: String): String
    }

    class GreeterImpl : Greeter {
        @LlmTool(name = "greet", description = "Greets someone")
        override fun greet(name: String): String = "Hello, $name"
    }

    private class CountingInterceptor : MethodInterceptor {
        var invocationCount = 0
            private set

        override fun invoke(invocation: MethodInvocation): Any? {
            invocationCount++
            return invocation.proceed()
        }
    }

    @Test
    fun `discovers tool on a plain instance`() {
        val tools = Tool.safelyFromInstance(GreetingTools())

        assertEquals(listOf("greet"), tools.map { it.definition.name })
    }

    @Test
    fun `discovers tool on a CGLIB-proxied instance`() {
        val proxyFactory = ProxyFactory(GreetingTools())
        proxyFactory.isProxyTargetClass = true
        val proxy = proxyFactory.proxy as GreetingTools

        val tools = Tool.safelyFromInstance(proxy)

        assertEquals(listOf("greet"), tools.map { it.definition.name })
    }

    @Test
    fun `invoking a tool discovered on a CGLIB proxy goes through the proxy`() {
        val interceptor = CountingInterceptor()
        val proxyFactory = ProxyFactory(GreetingTools())
        proxyFactory.isProxyTargetClass = true
        proxyFactory.addAdvice(interceptor)
        val proxy = proxyFactory.proxy as GreetingTools

        val tools = Tool.safelyFromInstance(proxy)
        val greet = tools.single { it.definition.name == "greet" }

        val result = greet.call("""{"name": "World"}""") as Tool.Result.Text

        assertEquals("Hello, World", result.content)
        assertEquals(1, interceptor.invocationCount)
    }

    @Test
    fun `CGLIB proxy of a class with no LlmTool methods yields empty list`() {
        val proxyFactory = ProxyFactory(NoToolsHere())
        proxyFactory.isProxyTargetClass = true
        val proxy = proxyFactory.proxy

        val tools = Tool.safelyFromInstance(proxy)

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `JDK dynamic proxy yields empty list without throwing`() {
        val proxyFactory = ProxyFactory(GreeterImpl())
        val proxy = proxyFactory.proxy

        val tools = Tool.safelyFromInstance(proxy)

        assertTrue(tools.isEmpty())
    }
}
