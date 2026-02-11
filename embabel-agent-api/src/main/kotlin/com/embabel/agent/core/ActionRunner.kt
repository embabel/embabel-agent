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
package com.embabel.agent.core

import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.hitl.AwaitableResponseException
import com.embabel.common.util.time
import org.slf4j.LoggerFactory
import java.time.Duration

interface ActionRunner {

    /**
     * Execute an action
     * @param processContext process moment
     */
    fun execute(
        processContext: ProcessContext,
    ): ActionStatus

    /**
     * Properties referenced from input variable
     * Say "person" is passed. Return "name" and other references
     */
    fun referencedInputProperties(variable: String): Set<String>

    companion object {

        private val logger = LoggerFactory.getLogger(ActionRunner::class.java)

        /**
         * Execute this operation with timings and error handling
         */
        fun execute(
            processContext: ProcessContext,
            block: () -> Unit,
        ): ActionStatus {
            val (status, ms) = time {
                try {
                    block()
                    ActionStatusCode.SUCCEEDED
                } catch (are: AwaitableResponseException) {
                    // Not an error condition
                    // Bind the awaitable to the blackboard
                    logger.debug(
                        "{} adding awaitable to blackboard: {}",
                        processContext.agentProcess.id,
                        are.awaitable.infoString(verbose = false),
                    )

                    processContext.blackboard.addObject(are.awaitable)
                    ActionStatusCode.WAITING
                } catch (rpe: ReplanRequestedException) {
                    // ReplanRequestedException is a control flow signal, not an error.
                    // Propagate it up to the agent process for replanning.
                    throw rpe
                } catch (t: Throwable) {
                    if (t is ToolControlFlowSignal) {
                        // Other control flow signals (e.g., UserInputRequiredException) must propagate
                        throw t
                    }
                    if (logger.isDebugEnabled) {
                        logger.debug(
                            "Unexpected error invoking action",
                            t,
                        )
                    } else {
                        logger.warn(
                            "Unexpected error invoking action: {}",
                            t.message,
                        )
                    }
                    throw t
                }
            }
            return ActionStatus(
                status = status,
                runningTime = Duration.ofMillis(ms),
            )
        }
    }

}
