/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.shell

import com.embabel.agent.api.common.autonomy.*
import com.embabel.agent.core.hitl.*
import com.embabel.agent.event.logging.personality.ColorPalette
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.chat.AgenticResultAssistantMessage
import com.embabel.chat.ChatSession
import com.embabel.chat.UserMessage
import com.embabel.common.util.AnsiColor
import com.embabel.common.util.color
import com.embabel.common.util.loggerFor
import com.embabel.ux.form.Button
import com.embabel.ux.form.FormSubmission
import com.embabel.ux.form.TextField
import com.fasterxml.jackson.databind.ObjectMapper
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.springframework.stereotype.Component

/**
 * Provide interaction and form support
 */
@Component
class TerminalServices(
    private val terminal: Terminal,
    private val objectMapper: ObjectMapper,
    private val shellProperties: ShellProperties,
) : GoalChoiceApprover {
    /**
     * Get further input
     */
    private fun <T> doWithReader(
        callback: (LineReader) -> T
    ): T {
        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build()
        return callback(lineReader)
    }

    fun chat(
        chatSession: ChatSession,
        colorPalette: ColorPalette,
    ): String {
        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build()
        lineReader.printAbove("Chat session started. Type 'exit' to end the session.")
        while (true) {
            val userInput = lineReader.readLine("You: ".color(colorPalette.highlight))
            if (userInput.equals("exit", ignoreCase = true)) {
                break
            }
            val userMessage = UserMessage(userInput)
            chatSession.respond(userMessage) {
                when (it) {
                    is UserMessage -> error("User message should not be sent by the assistant")
                    is AgenticResultAssistantMessage -> {
                        val formatted = formatProcessOutput(
                            result = it.agentProcessExecution,
                            colorPalette = colorPalette,
                            objectMapper = objectMapper,
                            lineLength = shellProperties.lineLength,
                        )
                        lineReader.printAbove("Assistant:\n$formatted")
                    }

                    else -> {
                        lineReader.printAbove("Assistant: ${it.content.color(colorPalette.color2)}")
                    }
                }
            }
        }

        return "Conversation finished"
    }

    /**
     * Handle the process waiting exception request
     * @return null if the operation was cancelled by the user
     */
    fun handleProcessWaitingException(processWaitingException: ProcessWaitingException): AwaitableResponse? {
        val awaitableResponse = when (processWaitingException.awaitable) {
            is ConfirmationRequest<*> -> {
                confirmationResponseFromUserInput(processWaitingException.awaitable as ConfirmationRequest<*>)
            }

            is FormBindingRequest<*> -> {
                formBindingResponseFromUserInput(processWaitingException.awaitable as FormBindingRequest<*>)
            }

            else -> {
                TODO("Unhandled awaitable: ${processWaitingException.awaitable.infoString()}")
            }
        }
        return awaitableResponse
    }

    fun confirm(message: String) = doWithReader {
        it.readLine("$message (y/n): ".color(AnsiColor.YELLOW))
            .equals("y", ignoreCase = true)
    }

    private fun confirmationResponseFromUserInput(
        confirmationRequest: ConfirmationRequest<*>,
    ): ConfirmationResponse {
        val confirmed = confirm(confirmationRequest.message)
        return ConfirmationResponse(
            awaitableId = confirmationRequest.id,
            accepted = confirmed,
        )
    }

    private fun formBindingResponseFromUserInput(
        formBindingRequest: FormBindingRequest<*>,
    ): FormResponse? {
        val form = formBindingRequest.payload
        val values = mutableMapOf<String, Any>()

        return doWithReader { lineReader ->
            loggerFor<ShellCommands>().info("Form: ${form.infoString()}")
            lineReader.printAbove(form.title)

            for (control in form.controls) {
                when (control) {
                    is TextField -> {
                        var input: String
                        var isValid = false

                        while (!isValid) {
                            val prompt =
                                "${control.label}${if (control.required) " *" else ""}: ".color(AnsiColor.YELLOW)
                            input = lineReader.readLine(prompt)//, control.value, null)

                            // Handle empty input for required fields
                            if (control.required && input.isBlank()) {
                                lineReader.printAbove("This field is required.")
                                continue
                            }

                            // Validate max length
                            if (control.maxLength != null && input.length > control.maxLength!!) {
                                lineReader.printAbove("Input exceeds maximum length of ${control.maxLength} characters.")
                                continue
                            }

                            // Validate pattern if specified
                            if (control.validationPattern != null && input.isNotBlank()) {
                                val regex = control.validationPattern!!.toRegex()
                                if (!input.matches(regex)) {
                                    lineReader.printAbove(
                                        control.validationMessage ?: "Input doesn't match required format."
                                    )
                                    continue
                                }
                            }

                            values[control.id] = input
                            isValid = true
                        }
                    }
                    // Add handling for other control types here as needed
                    // For example: Checkbox, RadioButton, Select, etc.
                    is Button -> {
                        // Handle submit button click
                        // TODO finish this
                    }

                    else -> {
                        // Handle unsupported control type
                        lineReader.printAbove("Unsupported control type: ${control.type}")
                    }
                }
            }

            val confirmSubmit = lineReader.readLine("Submit form? (y/n): ".color(AnsiColor.YELLOW))
                .equals("y", ignoreCase = true)

            if (!confirmSubmit) {
                null
            } else {
                FormResponse(
                    awaitableId = formBindingRequest.id,
                    formSubmission = FormSubmission(
                        formId = form.id,
                        values = values,
                    )
                )
            }
        }
    }

    override fun approve(goalChoiceApprovalRequest: GoalChoiceApprovalRequest): GoalChoiceApprovalResponse {
        val approved = confirm("Do you approve this goal: ${goalChoiceApprovalRequest.goal.description}?")
        return if (approved) {
            GoalChoiceApproved(
                request = goalChoiceApprovalRequest,
            )
        } else {
            GoalChoiceNotApproved(
                request = goalChoiceApprovalRequest,
                reason = "User said now",
            )
        }

    }

}
