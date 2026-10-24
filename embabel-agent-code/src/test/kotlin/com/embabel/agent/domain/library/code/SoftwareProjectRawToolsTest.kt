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
package com.embabel.agent.domain.library.code

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SoftwareProjectRawToolsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `rawTools returns non-empty tool list`() {
        val project = SoftwareProject(root = tempDir.toString())

        val tools = project.rawTools()

        assertTrue(tools.isNotEmpty(), "SoftwareProject must expose at least one LlmTool method")
    }
}
