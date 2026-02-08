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
package com.embabel.agent.api.reference

/**
 * Reference for the contents of a web page.
 * Works only if fetch tool is available.
 * See CoreToolGroups.WEB
 */
data class WebPage(
    val url: String,
    override val name: String = url,
    override val description: String = "Web page at $url",
) : LlmReferenceProvider, LlmReference {

    override fun reference(): LlmReference = this

    override fun notes(): String = "Refer to this web page: use the fetch tool"
}
