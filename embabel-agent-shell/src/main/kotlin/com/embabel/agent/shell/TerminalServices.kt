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

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.FileAppender
import com.embabel.agent.api.common.autonomy.*
import com.embabel.agent.channel.*
import com.embabel.agent.core.hitl.*
import com.embabel.agent.event.logging.personality.ColorPalette
import com.embabel.agent.event.logging.personality.DefaultColorPalette
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.chat.ChatSession
import com.embabel.chat.UserMessage
import com.embabel.common.util.AnsiColor
import com.embabel.common.util.color
import com.embabel.common.util.loggerFor
import com.embabel.ux.form.Button
import com.embabel.ux.form.FormSubmission
import com.embabel.ux.form.TextField
import org.apache.commons.text.WordUtils
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ch.qos.logback.classic.Logger as LogbackLogger


/**
 * Provide interaction and form support
 */
@Component
class TerminalServices(
    private val terminal: Terminal,
    private val shellProperties: ShellProperties,
) : GoalChoiceApprover {

    /**
     * Get further input
     */
    private fun <T> doWithLineReader(
        callback: (LineReader) -> T,
    ): T {
        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build()
        return callback(lineReader)
    }

    fun print(what: String) {
        doWithLineReader { it.printAbove(what) }
    }

    @JvmOverloads
    fun chat(
        chatSession: ChatSession,
        welcome: String? = null,
        colorPalette: ColorPalette = DefaultColorPalette(),
    ): String {
        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build()
        lineReader.printAbove(
            (welcome?.let { it + "\n" } ?: "") +
                    """
            Chat session ${chatSession.conversation.id} started. Type 'exit' to end the session.
            Type /help for available commands.
            """.trimIndent().color(colorPalette.highlight)
        )
        while (true) {
            val userInput = lineReader.readLine("You: ".color(colorPalette.highlight))
            if (userInput.equals("exit", ignoreCase = true)) {
                break
            }
            val userMessage = UserMessage(userInput)
            chatSession.onUserMessage(userMessage)
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

    fun confirm(message: String) = doWithLineReader {
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

        return doWithLineReader { lineReader ->
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

    fun outputChannel(): OutputChannel = TerminalOutputChannel()

    inner class TerminalOutputChannel(
        private val colorPalette: ColorPalette = DefaultColorPalette(),
    ) : OutputChannel {

        override fun send(event: OutputChannelEvent) {
            when (event) {
                is MessageOutputChannelEvent -> {
                    val formattedResponse = WordUtils.wrap(
                        "${event.message.sender}: ${event.message.content.color(colorPalette.color2)}",
                        shellProperties.lineLength,
                    )
                    println(formattedResponse)
                }

                is ContentOutputChannelEvent -> {
                    println("Content event: ${event.content}")
                }

                is ProgressOutputChannelEvent -> {
                    println("▶ ${event.message}")
                }

                is LoggingOutputChannelEvent -> {
                    println("🪵 ${event.message}")
                }

                else -> {
                    println(event.toString())
                }
            }
        }
    }

    /**
     * Redirects all logging to a file and returns a function to restore the original logging configuration.
     * This is useful during interactive chat sessions to prevent log output from interfering with the UI.
     */
    fun redirectLoggingToFile(filename: String): () -> Unit {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger

        // Store the current appenders to restore later
        val originalAppenders = mutableMapOf<String, Appender<ILoggingEvent>>()
        val appenderIterator = rootLogger.iteratorForAppenders()
        while (appenderIterator.hasNext()) {
            val appender = appenderIterator.next()
            originalAppenders[appender.name] = appender
            rootLogger.detachAppender(appender)
        }

        // Create a file appender for logs during chat
        val logsDir = Paths.get(System.getProperty("user.home"), ".embabel", "logs")
        Files.createDirectories(logsDir)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val logFile = logsDir.resolve("$filename-$timestamp.log")

        val fileAppender = FileAppender<ILoggingEvent>().apply {
            context = loggerContext
            name = filename
            file = logFile.toString()

            val encoder = PatternLayoutEncoder().apply {
                context = loggerContext
                pattern = "%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"
                start()
            }
            this.encoder = encoder
            start()
        }

        // Attach the file appender
        rootLogger.addAppender(fileAppender)

        loggerFor<TerminalServices>().info("Logs redirected to: $logFile")

        // Return a function to restore the original logging configuration
        return {
            // Stop and detach the file appender
            rootLogger.detachAppender(fileAppender)
            fileAppender.stop()

            // Re-attach the original appenders
            originalAppenders.values.forEach { appender ->
                rootLogger.addAppender(appender)
            }

            loggerFor<TerminalServices>().info("Logging to console resotred. Logs are available at: $logFile")
        }
    }

}
