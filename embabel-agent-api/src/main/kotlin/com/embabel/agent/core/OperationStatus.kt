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
package com.embabel.agent.core

import com.embabel.common.core.types.Timed
import java.time.Duration

/**
 * Stuck means we failed to find a plan from here
 */
enum class AgentProcessStatusCode {

    /** The process has not started yet */
    NOT_STARTED,

    /** The process is running without any known problems */
    RUNNING,

    /** The process has completed successfully */
    COMPLETED,

    /** Game over. The process has failed */
    FAILED,

    /** The process has been killed by an early termination policy */
    TERMINATED,

    /** The process has been killed by the user or platform, from outside */
    KILLED,

    /** The process cannot formulate a plan to progress. This does not necessarily mean failure. Something might change */
    STUCK,

    /** The process is waiting for user input or another external event */
    WAITING,

    /**
     * The process is running without error but has paused because
     * of scheduling policy.
     **/
    PAUSED,
}

enum class ActionStatusCode {
    /** The action succeeded */
    SUCCEEDED,

    /** The action failed */
    FAILED,

    /** The action result means we're waiting for user input or another external event */
    WAITING,

    PAUSED,
}

/**
 * Status of an agent or action
 */
interface OperationStatus<S> : Timed where S : Enum<S> {

    /**
     * Status of this operation
     */
    val status: S
}

/**
 * Status of action execution.
 * Concrete results of running the action will be side effects:
 * typically, changes to the ProcessContext blackboard.
 * This just indicates what happened.
 */
open class ActionStatus(
    override val runningTime: Duration,
    override val status: ActionStatusCode,
) : OperationStatus<ActionStatusCode>
