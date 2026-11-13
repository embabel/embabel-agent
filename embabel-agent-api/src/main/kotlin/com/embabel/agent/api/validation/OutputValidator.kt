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
package com.embabel.agent.api.validation

import com.embabel.agent.core.Blackboard
import com.embabel.common.core.validation.ValidationResult

/**
 * Programmatic validation of an object created by an LLM, for rules that
 * JSR-380 annotations cannot express or for classes we cannot annotate.
 *
 * Runs on the deserialized object together with any JSR-380 validation.
 * Errors from an invalid result are reported back to the LLM in the same
 * corrective retry, and if the retry is still invalid
 * [com.embabel.agent.core.support.InvalidLlmReturnTypeException] is thrown.
 */
fun interface OutputValidator<in T> {

    /**
     * @param output the object the LLM created
     * @param blackboard the blackboard of the running process, for rules that
     * depend on context such as the source the object was extracted from
     */
    fun validate(output: T, blackboard: Blackboard): ValidationResult
}
