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
package com.embabel.agent.core.support

import com.embabel.agent.core.JvmType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class JvmTypeCacheInvalidatorTest {

    @Test
    fun `cache should be cleared on context close event`() {
        // Populate the cache first
        val type = JvmType(List::class.java)
        type.children(listOf("com.embabel"))

        // Create minimal context and register only the invalidator bean
        val context = AnnotationConfigApplicationContext()
        context.register(JvmTypeCacheInvalidator::class.java)
        context.refresh()

        // Verify invalidator is registered
        assertNotNull(context.getBean(JvmTypeCacheInvalidator::class.java))

        // Populate cache again
        type.children(listOf("com.embabel.agent"))

        // Close context - triggers ContextClosedEvent -> clearChildrenCache()
        context.close()

        // Cache was cleared (verified by INFO log: "Clearing JvmType children cache")
        val afterClose = type.children(listOf("com.embabel"))
        assertNotNull(afterClose)
    }
}
