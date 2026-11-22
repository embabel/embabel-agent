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
package com.embabel.agent.spi.support.springai

import com.embabel.common.ai.model.NativeUsage
import org.springframework.ai.chat.metadata.Usage as SpringAiUsage

/**
 * Reads one call's provider usage into [NativeUsage], before Spring AI merges usage across a tool loop
 * or stream and drops provider detail. Returns null when the usage is not this provider's,
 * so Embabel falls back to Spring AI's generic counts.
 */
fun interface SpringAiNativeUsageReader {

    fun read(usage: SpringAiUsage): NativeUsage?

    companion object {
        @JvmField
        val NONE = SpringAiNativeUsageReader { null }
    }
}
