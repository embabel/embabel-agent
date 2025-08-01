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
package com.embabel.ux.form

import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.indent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

/**
 * Raw data from the user
 */
data class FormSubmission(
    val formId: String,
    val values: Map<String, Any>,
    val submissionId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
) : Timestamped


data class FormSubmissionResult(
    val submission: FormSubmission,
    val values: Map<String, ControlValue>,
    val valid: Boolean = true,
    val validationErrors: Map<String, String> = emptyMap(),
) : HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return if (verbose == true)
            toString().indent(indent)
        else
            "${javaClass.simpleName}(submissionId=${submission.submissionId}, valid=$valid, validationErrors=$validationErrors)"
    }
}

sealed class ControlValue {
    data class TextValue(val value: String) : ControlValue()
    data class BooleanValue(val value: Boolean) : ControlValue()
    data class NumberValue(val value: Double) : ControlValue()
    data class DateValue(val value: LocalDate) : ControlValue()
    data class TimeValue(val value: LocalTime) : ControlValue()
    data class OptionValue(val value: String) : ControlValue()
    data class MultiOptionValue(val values: List<String>) : ControlValue()
    data class FileValue(val fileIds: List<String>, val fileNames: List<String>) : ControlValue()
    object EmptyValue : ControlValue()
}

data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

interface FormProcessor {
    fun processSubmission(form: Form, submission: FormSubmission): FormSubmissionResult
}
