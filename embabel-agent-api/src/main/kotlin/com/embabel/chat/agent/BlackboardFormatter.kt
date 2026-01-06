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
package com.embabel.chat.agent

import com.embabel.agent.core.Blackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.common.core.types.HasInfoString

interface BlackboardEntryFormatter {

    fun format(entry: Any): String
}

object DefaultBlackboardEntryFormatter : BlackboardEntryFormatter {

    override fun format(entry: Any): String {
        return when (entry) {
            is HasInfoString -> entry.infoString(verbose = true, indent = 0)
            is HasContent -> entry.content
            else -> entry.toString()
        }
    }
}

/**
 * Present the context of the blackboard to the agent in a textual form.
 * Exclude conversation and user input.
 */
interface BlackboardFormatter {

    /**
     * Formats the conversation so far for the agent.
     * @return the formatted conversation
     */
    fun format(blackboard: Blackboard): String
}

// TODO could make a prompt contributor so we can get caching
class DefaultBlackboardFormatter(
    private val entryFormatter: BlackboardEntryFormatter = DefaultBlackboardEntryFormatter,
) : BlackboardFormatter {
    override fun format(blackboard: Blackboard): String {
        return blackboard.objects
            .filterNot { it is Conversation || it is Message || it is UserInput }
            .map { entryFormatter.format(it) }
            .joinToString(separator = "\n") { it.trim() }
    }
}
