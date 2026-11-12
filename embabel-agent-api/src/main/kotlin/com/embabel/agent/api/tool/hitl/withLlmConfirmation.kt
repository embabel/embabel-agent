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

import com.embabel.agent.api.tool.Tool
import org.jetbrains.annotations.ApiStatus

/**
 * Wrap this tool so the LLM must obtain the user's confirmation before it runs.
 * Remember to expose the guard's [ConfirmationGuardedTool.verdictTool] too, see
 * [ConfirmationGuardedTool.tools]. Java callers use [ConfirmationGuardedTool.of].
 *
 * @param message Static confirmation message shown to the user
 * @param mode How the proposal reaches the human
 * @param options Wording and naming knobs
 */
@ApiStatus.Experimental
fun Tool.withLlmConfirmation(
    message: String,
    mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
    options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
): ConfirmationGuardedTool = ConfirmationGuardedTool(this, { message }, mode, options)

/**
 * Wrap this tool so the LLM must obtain the user's confirmation before it runs.
 * Remember to expose the guard's [ConfirmationGuardedTool.verdictTool] too, see
 * [ConfirmationGuardedTool.tools]. Java callers use [ConfirmationGuardedTool.of].
 *
 * @param mode How the proposal reaches the human
 * @param options Wording and naming knobs
 * @param messageProvider Builds the confirmation message from the raw tool input
 */
@ApiStatus.Experimental
fun Tool.withLlmConfirmation(
    mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
    options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
    messageProvider: (String) -> String,
): ConfirmationGuardedTool = ConfirmationGuardedTool(this, messageProvider, mode, options)
