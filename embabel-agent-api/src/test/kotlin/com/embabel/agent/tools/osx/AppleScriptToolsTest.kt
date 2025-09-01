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
package com.embabel.agent.tools.osx

import com.embabel.agent.common.Constants
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.common.core.types.Semver
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppleScriptToolsTest {

    private lateinit var appleScriptTools: AppleScriptTools

    @BeforeEach
    fun setup() {
        appleScriptTools = AppleScriptTools()
    }

    @Test
    fun testToolGroupMetadata() {
        assertEquals(Semver(0, 1, 0), appleScriptTools.version)
        assertEquals(Constants.EMBABEL_PROVIDER, appleScriptTools.provider)
        assertEquals("AppleScript", appleScriptTools.description.role)
        assertEquals("Run AppleScript commands", appleScriptTools.description.description)
        assertTrue(appleScriptTools.permissions.contains(ToolGroupPermission.HOST_ACCESS))
    }

    @Test
    fun testRunAppleScriptSuccess() {
        val mockRuntime = mockk<Runtime>()
        val mockProcess = mockk<Process>()

        mockkStatic(Runtime::class) {
            every { Runtime.getRuntime() } returns mockRuntime
            every { mockRuntime.exec(arrayOf("osascript", "-e", "display dialog \"Hello World\"")) } returns mockProcess
            every { mockProcess.waitFor() } returns 0

            val result = appleScriptTools.runAppleScript("display dialog \"Hello World\"")

            assertEquals("Script executed with exit code: 0", result)
            verify { mockRuntime.exec(arrayOf("osascript", "-e", "display dialog \"Hello World\"")) }
            verify { mockProcess.waitFor() }
        }
    }

    @Test
    fun testRunAppleScriptWithError() {
        val mockRuntime = mockk<Runtime>()
        val mockProcess = mockk<Process>()

        mockkStatic(Runtime::class) {
            every { Runtime.getRuntime() } returns mockRuntime
            every { mockRuntime.exec(arrayOf("osascript", "-e", "invalid script")) } returns mockProcess
            every { mockProcess.waitFor() } returns 1

            val result = appleScriptTools.runAppleScript("invalid script")

            assertEquals("Script executed with exit code: 1", result)
            verify { mockRuntime.exec(arrayOf("osascript", "-e", "invalid script")) }
            verify { mockProcess.waitFor() }
        }
    }

    @Test
    fun testRunAppleScriptWithComplexScript() {
        val complexScript = "tell application \"Finder\" to get name of every folder of desktop"
        val mockRuntime = mockk<Runtime>()
        val mockProcess = mockk<Process>()

        mockkStatic(Runtime::class) {
            every { Runtime.getRuntime() } returns mockRuntime
            every { mockRuntime.exec(arrayOf("osascript", "-e", complexScript)) } returns mockProcess
            every { mockProcess.waitFor() } returns 0

            val result = appleScriptTools.runAppleScript(complexScript)

            assertEquals("Script executed with exit code: 0", result)
            verify { mockRuntime.exec(arrayOf("osascript", "-e", complexScript)) }
        }
    }

    @Test
    fun testRunAppleScriptProcessException() {
        val mockRuntime = mockk<Runtime>()

        mockkStatic(Runtime::class) {
            every { Runtime.getRuntime() } returns mockRuntime
            every { mockRuntime.exec(arrayOf("osascript", "-e", "test script")) } throws RuntimeException("Process execution failed")

            try {
                appleScriptTools.runAppleScript("test script")
            } catch (e: RuntimeException) {
                assertEquals("Process execution failed", e.message)
            }
        }
    }
}
