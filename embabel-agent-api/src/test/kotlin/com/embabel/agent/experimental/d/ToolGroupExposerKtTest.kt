/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.experimental.d

import com.embabel.agent.core.ToolCallbackPublisher
import com.embabel.agent.experimental.dsl.exposeAsInterface
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.annotation.Tool
import kotlin.test.assertEquals

interface MagicToolsNoArg {

    fun absquatulate(): String

    fun absquatulateWithArgs(arg1: String, arg2: Int): String
}

class MagicToolsImpl : MagicToolsNoArg {

    @Tool(description = "absquatulate")
    override fun absquatulate(): String {
        return "John Doe"
    }

    @Tool(description = "absquatulateWithArgs")
    override fun absquatulateWithArgs(arg1: String, arg2: Int): String {
        return "$arg1 $arg2"
    }
}

class ToolGroupExposerKtTest {

    @Nested
    inner class InvalidUse {

        @Test
        fun `unrelated interface`() {
            val toolGroup = ToolCallbackPublisher.Companion()
            assertThrows<IllegalArgumentException> {
                exposeAsInterface(toolGroup, MagicToolsNoArg::class.java)
            }
        }
    }

    @Nested
    inner class ValidUse {

        @Test
        fun `valid call without args`() {
            val toolGroup = ToolCallbackPublisher.Companion(
                ToolCallbacks.from(MagicToolsImpl()).toList(),
            )
            val magicTool = exposeAsInterface(toolGroup, MagicToolsNoArg::class.java)
            assertEquals(magicTool.absquatulateWithArgs("a", 18), "\"a 18\"")
        }

        @Test
        fun `valid call with args`() {
            val toolGroup = ToolCallbackPublisher(
                ToolCallbacks.from(MagicToolsImpl()).toList(),
            )
            val magicTool = exposeAsInterface(toolGroup, MagicToolsNoArg::class.java)
            assertEquals(magicTool.absquatulate(), "\"John Doe\"")
        }

    }

}
