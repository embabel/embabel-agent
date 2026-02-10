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
package com.embabel.agent.core.hitl

import com.embabel.agent.api.tool.Tool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AwaitableTypedToolTest {

    // Test fixtures
    data class DeleteRequest(val path: String, val force: Boolean = false)
    data class DeleteResult(val deleted: Boolean, val path: String)

    class ConfirmingDeleteTool : AwaitableTypedTool<DeleteRequest, DeleteResult, DeleteRequest>(
        name = "delete_file",
        description = "Delete a file with confirmation",
        inputType = DeleteRequest::class.java,
        outputType = DeleteResult::class.java,
    ) {
        override fun createAwaitable(input: DeleteRequest): Awaitable<DeleteRequest, *>? {
            return if (!input.force) {
                ConfirmationRequest(input, "Delete ${input.path}?")
            } else null
        }

        override fun execute(input: DeleteRequest): DeleteResult {
            return DeleteResult(deleted = true, path = input.path)
        }
    }

    @Nested
    inner class AwaitableCreation {

        @Test
        fun `throws AwaitableResponseException when awaitable is created`() {
            val tool = ConfirmingDeleteTool()

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("""{"path": "/tmp/test.txt", "force": false}""")
            }

            assertThat(exception.awaitable).isInstanceOf(ConfirmationRequest::class.java)
            val confirmation = exception.awaitable as ConfirmationRequest<*>
            assertThat(confirmation.message).isEqualTo("Delete /tmp/test.txt?")
            assertThat(confirmation.payload).isInstanceOf(DeleteRequest::class.java)
        }

        @Test
        fun `executes normally when no awaitable is created`() {
            val tool = ConfirmingDeleteTool()

            val result = tool.call("""{"path": "/tmp/test.txt", "force": true}""")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            val text = (result as Tool.Result.Text).content
            assertThat(text).contains("\"deleted\":true")
            assertThat(text).contains("\"/tmp/test.txt\"")
        }
    }

    @Nested
    inner class SimpleAwaitableTypedToolTests {

        @Test
        fun `functional creation works`() {
            val tool = SimpleAwaitableTypedTool(
                name = "confirm_action",
                description = "Action requiring confirmation",
                inputType = DeleteRequest::class.java,
                outputType = DeleteResult::class.java,
                awaitableFactory = { input ->
                    if (!input.force) ConfirmationRequest(input, "Confirm?") else null
                },
                executor = { input ->
                    DeleteResult(deleted = true, path = input.path)
                }
            )

            // Should throw when force=false
            val exception = assertThrows<AwaitableResponseException> {
                tool.call("""{"path": "/test", "force": false}""")
            }
            assertThat(exception.awaitable).isInstanceOf(ConfirmationRequest::class.java)

            // Should execute when force=true
            val result = tool.call("""{"path": "/test", "force": true}""")
            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
        }

        @Test
        fun `always awaiting tool`() {
            val tool = SimpleAwaitableTypedTool(
                name = "always_confirm",
                description = "Always requires confirmation",
                inputType = DeleteRequest::class.java,
                outputType = DeleteResult::class.java,
                awaitableFactory = { input ->
                    ConfirmationRequest(input, "Are you sure?")
                },
                executor = { input ->
                    DeleteResult(deleted = true, path = input.path)
                }
            )

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("""{"path": "/any", "force": true}""")
            }
            assertThat(exception.awaitable).isNotNull
        }

        @Test
        fun `never awaiting tool`() {
            val tool = SimpleAwaitableTypedTool<DeleteRequest, DeleteResult, DeleteRequest>(
                name = "never_confirm",
                description = "Never requires confirmation",
                inputType = DeleteRequest::class.java,
                outputType = DeleteResult::class.java,
                awaitableFactory = { null },
                executor = { input ->
                    DeleteResult(deleted = true, path = input.path)
                }
            )

            val result = tool.call("""{"path": "/any", "force": false}""")
            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
        }
    }

    @Nested
    inner class DefinitionTests {

        @Test
        fun `definition is generated correctly`() {
            val tool = ConfirmingDeleteTool()

            assertThat(tool.definition.name).isEqualTo("delete_file")
            assertThat(tool.definition.description).isEqualTo("Delete a file with confirmation")

            val schema = tool.definition.inputSchema.toJsonSchema()
            assertThat(schema).contains("\"path\"")
            assertThat(schema).contains("\"force\"")
        }
    }
}
