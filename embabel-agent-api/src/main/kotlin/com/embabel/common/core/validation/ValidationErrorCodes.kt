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
package com.embabel.common.core.validation

/**
 * Well-known error codes used in [ValidationError] instances produced by the built-in validators.
 */
class ValidationErrorCodes {
    companion object {
        /** Agent class has no @Action or @Condition methods and no goals defined. */
        const val EMPTY_AGENT_STRUCTURE = "EMPTY_AGENT_STRUCTURE"

        /** Agent has no goals, so it can never be considered complete. */
        const val MISSING_GOALS = "MISSING_GOALS"

        /** Two or more actions in the same agent share the same name, typically caused by overloaded @Action methods. */
        const val DUPLICATE_ACTION_NAME = "DUPLICATE_ACTION_NAME"

        /** An action has preconditions whose backing methods take more than one parameter. */
        const val INVALID_ACTION_SIGNATURE = "INVALID_ACTION_SIGNATURE"

        /** A condition method takes more than one parameter. */
        const val INVALID_CONDITION_SIGNATURE = "INVALID_CONDITION_SIGNATURE"

        /** Agent has goals but no actions, so no plan can ever reach them. */
        const val NO_ACTIONS_TO_GOALS = "NO_ACTIONS_TO_GOALS"
    }
}
