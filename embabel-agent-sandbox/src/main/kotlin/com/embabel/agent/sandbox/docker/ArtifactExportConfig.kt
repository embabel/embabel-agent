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
package com.embabel.agent.sandbox.docker

import java.nio.file.Path
import java.time.Duration

/**
 * Public, opt-in publication settings for applications creating persistent Docker sessions.
 * The application owns [hostRoot], published files, retention, and download access.
 * [maxFileBytes], [maxArchiveBytes], and [maxEntries] apply per selected file.
 * [maxFiles], [maxTotalBytes], and [timeout] apply to the whole batch, including
 * waiting for the session lock, transfer, and validation. [maxTotalBytes] counts
 * successful file payloads; failed transfers still consume time and archive allowance.
 */
data class ArtifactExportConfig @JvmOverloads constructor(
    val hostRoot: Path,
    val maxFileBytes: Long = 10L * 1024 * 1024,
    val maxArchiveBytes: Long = 12L * 1024 * 1024,
    val maxEntries: Int = 32,
    val timeout: Duration = Duration.ofSeconds(30),
    val maxFiles: Int = 20,
    val maxTotalBytes: Long = 50L * 1024 * 1024,
) {
    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxArchiveBytes >= maxFileBytes && maxArchiveBytes >= 1024) { "Invalid archive byte limit" }
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxFiles > 0) { "maxFiles must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofHours(1)) { "Invalid publication timeout" }
    }
}
