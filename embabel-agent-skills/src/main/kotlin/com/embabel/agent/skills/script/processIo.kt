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
package com.embabel.agent.skills.script

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Outcome of running a process with deadlock-safe I/O pumping.
 *
 * [exitCode] is null exactly when the process timed out ([timedOut] is true).
 */
internal data class ProcessIoResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * Run a started [Process] to completion (or [timeout]) without deadlocking.
 *
 * stdout and stderr are drained on their own threads STARTED BEFORE stdin is written, so
 * a process that produces output while still consuming stdin cannot deadlock on a full
 * pipe. stdin is written on a third thread, and an [IOException] (the process closed its
 * stdin / exited early) is swallowed rather than failing the whole execution. On timeout
 * the process is force-killed. All three threads are daemon so they never block JVM exit.
 *
 * Both script engines share this so the deadlock-safe pattern lives in one place instead
 * of being copy-pasted (and diverging) per engine.
 */
internal fun Process.pumpIo(stdin: String?, timeout: Duration): ProcessIoResult {
    var stdout = ""
    var stderr = ""
    val stdoutThread = Thread { stdout = inputStream.bufferedReader().readText() }
        .apply { isDaemon = true; start() }
    val stderrThread = Thread { stderr = errorStream.bufferedReader().readText() }
        .apply { isDaemon = true; start() }
    val stdinThread = Thread {
        try {
            if (stdin != null) {
                outputStream.bufferedWriter().use { it.write(stdin) }
            } else {
                outputStream.close()
            }
        } catch (e: IOException) {
            // The process may close its stdin / exit before consuming all input.
        }
    }.apply { isDaemon = true; start() }

    val completed = waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    if (!completed) {
        destroyForcibly()
        stdinThread.join(1_000)
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
        return ProcessIoResult(exitCode = null, stdout = stdout, stderr = stderr, timedOut = true)
    }

    stdinThread.join()
    stdoutThread.join()
    stderrThread.join()
    return ProcessIoResult(exitCode = exitValue(), stdout = stdout, stderr = stderr, timedOut = false)
}
