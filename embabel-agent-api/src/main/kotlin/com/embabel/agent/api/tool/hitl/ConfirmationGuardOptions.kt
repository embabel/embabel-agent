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
package com.embabel.agent.api.tool.hitl

import org.jetbrains.annotations.ApiStatus

/**
 * Wording and naming options for [ConfirmationGuardedTool].
 * Immutable; derive variants with [withConfirmationNote] and [withVerdictToolPrefix] or `copy`.
 *
 * @param confirmationNote Sentence appended to the guarded tool's description. Blank means
 * the delegate's description is left untouched.
 * @param verdictToolPrefix Prefix for the verdict tool's name, followed by the guarded tool's
 * name. Must not be blank, or the two tools would share a name.
 */
@ApiStatus.Experimental
data class ConfirmationGuardOptions @JvmOverloads constructor(
    val confirmationNote: String = DEFAULT_CONFIRMATION_NOTE,
    val verdictToolPrefix: String = DEFAULT_VERDICT_TOOL_PREFIX,
) {

    init {
        require(verdictToolPrefix.isNotBlank()) { "verdictToolPrefix must not be blank" }
    }

    fun withConfirmationNote(confirmationNote: String): ConfirmationGuardOptions =
        copy(confirmationNote = confirmationNote)

    fun withVerdictToolPrefix(verdictToolPrefix: String): ConfirmationGuardOptions =
        copy(verdictToolPrefix = verdictToolPrefix)

    companion object {
        const val DEFAULT_CONFIRMATION_NOTE = "Requires the user's confirmation before it takes effect."
        const val DEFAULT_VERDICT_TOOL_PREFIX = "confirm_"

        @JvmField
        val DEFAULT = ConfirmationGuardOptions()
    }
}
