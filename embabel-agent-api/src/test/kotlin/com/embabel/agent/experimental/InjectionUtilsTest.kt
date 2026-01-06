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
package com.embabel.agent.experimental.util

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Service

@Service
class Bar {
    val foo = Foo()
}

@Configurable
class TestEntity : InitializingBean, Injectable {
    var afterPropertiesSetCalled = false

    @Autowired
    lateinit var bar: Bar

    @Value("\${foo}")
    lateinit var fooValue: String

    override fun afterPropertiesSet() {
        this.afterPropertiesSetCalled = true
    }

    override fun additionalInjectees(): List<Any> {
        return listOf(this.bar.foo)
    }
}

@Service
class Baz

@Configurable
class Foo {
    @Autowired
    lateinit var baz: Baz
}

class NotAtConfigurableFoo {
    @Autowired
    lateinit var baz: Baz
}

@Configurable
class Complex : Injectable {
    @Autowired
    lateinit var bar: Bar

    @Value("\${foo}")
    lateinit var fooValue: String

    override fun additionalInjectees(): List<Any> {
        return listOf(this.bar.foo)
    }
}

class InjectionUtilsTest {

    @Nested
    inner class ViaInjectingListener {

        @Test
        fun `test @Value`() {
            val ac = AnnotationConfigApplicationContext()
            ac.register(Bar::class.java, Baz::class.java)
            ac.refresh()
            val complex = Complex()
            Injector(ac).inject(complex)
            assertNotNull(complex.fooValue, "@Value should have been injected")
        }

        @Test
        fun `test @Autowired`() {
            val ac = AnnotationConfigApplicationContext()
            ac.register(Bar::class.java, Baz::class.java)
            ac.refresh()
            val foo = Foo()
            Injector(ac).inject(foo)
            assertNotNull(foo.baz, "Baz should have been injected")
        }

        @Test
        fun `test with additional injectees`() {
            val ac = AnnotationConfigApplicationContext()
            ac.register(Bar::class.java, Baz::class.java)
            ac.refresh()
            val complex = Complex()
            Injector(ac).inject(complex)
            assertNotNull(complex.bar.foo.baz, "Should have injected additional injectees")
        }

        @Test
        fun `test ignore without @Configurable`() {
            val ac = AnnotationConfigApplicationContext()
            ac.register(Baz::class.java)
            ac.refresh()
            val notfoo = NotAtConfigurableFoo()
            Injector(ac).inject(notfoo)
            assertThrows<UninitializedPropertyAccessException> {
                assertNull(notfoo.baz, "Should not have injected unannotated bean")
            }
        }
    }

    @Nested
    inner class Direct {

        @Nested
        inner class Autowiring {

            @Test
            fun `test @Value`() {
                val ac = AnnotationConfigApplicationContext()
                ac.register(Bar::class.java, Baz::class.java)
                ac.refresh()
                val complex = Complex()
                val wired = InjectionUtils.wire(complex, ac)
                assertNotNull(wired!!.fooValue, "@Value should have been injected")
            }

            @Test
            fun `test @Autowired`() {
                val ac = AnnotationConfigApplicationContext()
                ac.register(Bar::class.java, Baz::class.java)
                ac.refresh()
                val foo = Foo()
                val wired = InjectionUtils.wire(foo, ac)
                assertNotNull(wired!!.baz, "Baz should have been injected")
            }

            @Test
            fun `test with additional injectees`() {
                val ac = AnnotationConfigApplicationContext()
                ac.register(Bar::class.java, Baz::class.java)
                ac.refresh()
                val complex = Complex()
                val wired = InjectionUtils.wire(complex, ac)
                assertNotNull(wired!!.bar.foo.baz, "Should have injected additional injectees")
            }
        }
    }
}
