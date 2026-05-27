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
package com.embabel.agent.api.models

/**
 * Constants for OCI Generative AI model identifiers.
 */
class OciGenAiModels {

    companion object {

        const val GOOGLE_GEMINI_2_5_PRO = "google.gemini-2.5-pro"
        const val GOOGLE_GEMINI_2_5_FLASH = "google.gemini-2.5-flash"

        const val OPENAI_GPT_OSS_120B = "openai.gpt-oss-120b"

        const val XAI_GROK_4_3 = "xai.grok-4.3"

        const val COHERE_COMMAND_A_REASONING = "cohere.command-a-reasoning"
        const val COHERE_COMMAND_A_VISION = "cohere.command-a-vision"
        const val COHERE_COMMAND_A = "cohere.command-a-03-2025"

        const val META_LLAMA_4_MAVERICK = "meta.llama-4-maverick-17b-128e-instruct-fp8"
        const val META_LLAMA_4_SCOUT = "meta.llama-4-scout-17b-16e-instruct"
        const val META_LLAMA_3_3_70B = "meta.llama-3.3-70b-instruct"

        const val COHERE_EMBED_V4 = "cohere.embed-v4.0"

        const val PROVIDER = "OCIGenAI"
    }
}
