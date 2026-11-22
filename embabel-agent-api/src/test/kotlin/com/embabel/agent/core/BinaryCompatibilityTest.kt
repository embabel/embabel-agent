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
package com.embabel.agent.core

import com.embabel.common.ai.model.PerTokenPricingModel
import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage

/**
 * Callers compiled against 1.5.x link to these exact JVM descriptors (from `javap -s` on the released classes).
 */
class BinaryCompatibilityTest {

    private val int = Int::class.javaObjectType
    private val obj = Any::class.java
    private val dbl = Double::class.javaObjectType
    private val marker = DefaultConstructorMarker::class.java

    @Test
    fun `Usage keeps its released constructors, accessors and copy`() {
        val u = Usage::class.java
        u.getConstructor(int, int, obj)
        u.getConstructor(int, int, obj, int)
        u.getConstructor(int, int, obj, int, int)
        val synthetic = u.getDeclaredConstructor(int, int, obj, int, int, Int::class.java, marker)
        assertEquals(obj, u.getMethod("getNativeUsage").returnType)
        assertEquals(obj, u.getMethod("component3").returnType)
        assertEquals(u, u.getMethod("copy", int, int, obj).returnType)
        val copy = u.getMethod("copy", int, int, obj, int, int)
        assertEquals(u, copy.returnType)
        val copyDefault = u.getMethod("copy\$default", u, int, int, obj, int, int, Int::class.java, obj)
        u.getMethod("copy\$default", u, int, int, obj, Int::class.java, obj)

        // Usage(100, 20, null, cacheWriteInputTokens = 10): argument 3 omitted.
        val created = synthetic.newInstance(100, 20, null, null, 10, 1 shl 3, null) as Usage
        assertEquals(Usage(100, 20, null, null, 10), created)
        // u.copy(promptTokens = 200): every argument but the first omitted.
        val copied = copyDefault.invoke(null, created, 200, null, null, null, null, 0b11110, null) as Usage
        assertEquals(200, copied.promptTokens)
        assertEquals(10, copied.cacheWriteInputTokens)
        assertNull(copied.cacheReadInputTokens)
    }

    @Test
    fun `toEmbabelUsage keeps its released overloads and default bridge`() {
        val k = Class.forName("com.embabel.agent.spi.support.springai.SpringAiUtilsKt")
        val springUsage = org.springframework.ai.chat.metadata.Usage::class.java
        k.getMethod("toEmbabelUsage", springUsage)
        k.getMethod("toEmbabelUsage", springUsage, Boolean::class.java)
        val default = k.getMethod("toEmbabelUsage\$default", springUsage, Boolean::class.java, Int::class.java, obj)
        // DefaultUsage(100, 20).toEmbabelUsage(): argument 0 omitted.
        val usage = default.invoke(null, DefaultUsage(100, 20), false, 1, null) as Usage
        assertEquals(100, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
    }

    @Test
    fun `PerTokenPricingModel keeps its released constructors`() {
        val p = PerTokenPricingModel::class.java
        val d = Double::class.java
        p.getConstructor(d, d)
        p.getConstructor(d, d, dbl)
        p.getConstructor(d, d, dbl, dbl)
        val synthetic = p.getDeclaredConstructor(d, d, dbl, dbl, Int::class.java, marker)
        // PerTokenPricingModel(2.0, 10.0): arguments 2 and 3 omitted.
        val model = synthetic.newInstance(2.0, 10.0, null, null, 0b1100, null) as PerTokenPricingModel
        assertEquals(2.0, model.usdPer1mInputTokens)
        assertNull(model.usdPer1mCacheWriteInputTokens)
    }
}
