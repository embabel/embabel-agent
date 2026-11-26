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
package com.embabel.agent.sandbox

/**
 * Optional capability for exporting selected files from an existing persistent session.
 * This interface is open so other [SandboxSession] implementations can support publication.
 */
interface ArtifactPublishingSession {
    /**
     * Publish one or more selected files at normalized absolute container paths.
     * Each call creates independent host copies; repeat calls do not deduplicate.
     * The application owns returned files, including retention after session closure.
     * Implementations enforce aggregate limits and coordinate with session lifecycle operations.
     * Invalid requests or failure to start publication throw; individual file failures
     * are returned in the result. Interruption preserves the interrupt flag.
     * Successful files remain available even if another path fails or the batch is interrupted.
     */
    fun publishFiles(containerPaths: List<String>): ArtifactPublication
}

/** A published artifact and the container path that was requested for it. */
data class PublishedFile(
    val containerPath: String,
    val artifact: ExecutionArtifact.File,
)

/** Files successfully exported and diagnostics keyed by the requested container path. */
data class ArtifactPublication(
    val files: List<PublishedFile>,
    val failures: Map<String, String> = emptyMap(),
)
