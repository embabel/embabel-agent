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
package com.embabel.agent.api.annotation

import com.embabel.agent.api.common.ActionContext

/**
 * Superclass for exceptions thrown by special return mechanisms like subagent execution.
 * These exceptions are caught by the agent runtime to handle the special return.
 * Throwing exceptions allows us to retain strong typing in the action method signatures.
 * @param message The exception message. Informative only.
 * @param type The expected return type of the action method.
 */
abstract class SpecialReturnException(
    message: String,
    val type: Class<*>,
) : RuntimeException(message) {

    /**
     * Process the special return and produce the final result.
     * This should be of the expected type, although Exception classes cannot be parameterized.
     */
    abstract fun handle(actionContext: ActionContext): Any
}
