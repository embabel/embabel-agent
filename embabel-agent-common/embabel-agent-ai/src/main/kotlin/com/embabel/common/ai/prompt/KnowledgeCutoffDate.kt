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
package com.embabel.common.ai.prompt

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Well-known prompt contributor for knowledge cutoff date.
 * Valuable if the knowledge cutoff date is known.
 * Added by Embabel agent platform.
 */
class KnowledgeCutoffDate(
    val date: LocalDate,
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
) : PromptContributor {

    override fun contribution() = "Knowledge cutoff: ${date.format(formatter)}\n"

    override val role = PromptContribution.KNOWLEDGE_CUTOFF_ROLE

    override fun toString(): String = "KnowledgeCutoffDate: [${contribution()}]"

}
