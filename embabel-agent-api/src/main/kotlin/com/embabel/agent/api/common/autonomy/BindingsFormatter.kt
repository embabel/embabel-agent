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
package com.embabel.agent.api.common.autonomy

/**
 * Formats bindings into a string representation suitable for goal ranking and intent display.
 *
 * This allows [Autonomy.chooseAndAccomplishGoal] to work with arbitrary bindings,
 * not just UserInput.
 */
fun interface BindingsFormatter {

    /**
     * Format the bindings map into a string representation.
     * This string is used for:
     * - Goal ranking (comparing against goal descriptions)
     * - Intent display in approval requests
     * - Logging and event tracking
     *
     * @param bindings the bindings map passed to chooseAndAccomplishGoal
     * @return a string representation suitable for ranking and display
     */
    fun format(bindings: Map<String, Any>): String

    companion object {
        /**
         * Default formatter that formats each binding value using:
         * 1. PromptContributor.contribution() if implemented
         * 2. HasInfoString.infoString() if implemented
         * 3. toString() otherwise
         *
         * Multiple bindings are joined with newlines.
         */
        val DEFAULT: BindingsFormatter = DefaultBindingsFormatter()
    }
}
