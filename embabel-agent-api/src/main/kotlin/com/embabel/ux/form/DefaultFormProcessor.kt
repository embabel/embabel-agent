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
package com.embabel.ux.form

import java.time.LocalDate
import java.time.LocalTime

fun interface FormValidator {
    fun validate(
        value: ControlValue,
        control: Control,
    ): ValidationResult
}

class RequiredFormValidator : FormValidator {
    override fun validate(
        value: ControlValue,
        control: Control,
    ): ValidationResult {
        val isEmpty = when (value) {
            is ControlValue.TextValue -> value.value.isBlank()
            is ControlValue.BooleanValue -> !value.value // For checkbox "required" means it must be checked
            is ControlValue.OptionValue -> value.value.isBlank()
            is ControlValue.MultiOptionValue -> value.values.isEmpty()
            is ControlValue.FileValue -> value.fileIds.isEmpty()
            is ControlValue.DateValue, is ControlValue.TimeValue, is ControlValue.NumberValue -> false
            is ControlValue.EmptyValue -> true
        }

        return if (isEmpty) {
            ValidationResult(false, "This field is required")
        } else {
            ValidationResult(true)
        }
    }
}


class PatternFormValidator(
    private val pattern: String,
    private val errorMessage: String,
) : FormValidator {
    override fun validate(
        value: ControlValue,
        control: Control,
    ): ValidationResult {
        return when (value) {
            is ControlValue.TextValue -> {
                if (value.value.matches(Regex(pattern))) {
                    ValidationResult(true)
                } else {
                    ValidationResult(false, errorMessage)
                }
            }

            else -> ValidationResult(true) // Only applicable to text values
        }
    }
}

class DoubleRangeFormValidator(
    private val min: Double,
    private val max: Double,
    private val errorMessage: String,
) :
    FormValidator {
    override fun validate(
        value: ControlValue,
        control: Control,
    ): ValidationResult {
        return when (value) {
            is ControlValue.NumberValue -> {
                if (value.value in min..max) {
                    ValidationResult(true)
                } else {
                    ValidationResult(false, errorMessage)
                }
            }

            else -> ValidationResult(true) // Only applicable to text values
        }
    }
}

class DropDownFormValidator(
    private val options: List<String>,
    private val errorMessage: String,
) : FormValidator {
    override fun validate(
        value: ControlValue,
        control: Control,
    ): ValidationResult {
        return when (value) {
            is ControlValue.OptionValue -> {
                if (options.contains(value.value)) {
                    ValidationResult(true)
                } else {
                    ValidationResult(false, errorMessage)
                }
            }

            else -> ValidationResult(true)
        }
    }
}

class DefaultFormProcessor : FormProcessor {

    override fun processSubmission(
        form: Form,
        submission: FormSubmission,
    ): FormSubmissionResult {
        require(form.id == submission.formId) {
            "Form ID in submission does not match the form ID"
        }
        val validators = defaultValidatorsFor(form)
        val processedValues = mutableMapOf<String, ControlValue>()
        val errors = mutableMapOf<String, String>()

        // Process each control in the form
        form.controls
            .filterNot {
                it is RequirableControl && it.disabled
            }
            .forEach { control ->
                val rawValue = submission.values[control.id]

                // Convert raw value to typed ControlValue
                val controlValue = convertToControlValue(rawValue, control)
                processedValues[control.id] = controlValue

                // Validate the value if validators exist for this control
                validators[control.id]?.forEach { validator ->
                    val result = validator.validate(controlValue, control)
                    if (!result.isValid) {
                        errors[control.id] = result.errorMessage ?: "Invalid value"
                    }
                }
            }

        return FormSubmissionResult(
            submission = submission,
            values = processedValues,
            valid = errors.isEmpty(),
            validationErrors = errors
        )
    }

    private fun convertToControlValue(
        value: Any?,
        control: Control,
    ): ControlValue {
        if (value == null) return ControlValue.EmptyValue

        return when (control) {
            is TextField, is TextArea -> ControlValue.TextValue(value.toString())
            is Checkbox, is Toggle -> ControlValue.BooleanValue(value as Boolean)
            is Slider -> ControlValue.NumberValue(value as Double)
            is DatePicker -> {
                if (value is LocalDate) ControlValue.DateValue(value)
                else ControlValue.DateValue(LocalDate.parse(value.toString()))
            }

            is TimePicker -> {
                if (value is LocalTime) ControlValue.TimeValue(value)
                else ControlValue.TimeValue(LocalTime.parse(value.toString()))
            }

            is Dropdown -> {
                ControlValue.OptionValue(value.toString())
            }

            is RadioGroup -> ControlValue.OptionValue(value.toString())
            is FileUpload -> {
                if (value is Map<*, *>) {
                    val fileIds = (value["ids"] as? List<*>)?.map { it.toString() } ?: emptyList()
                    val fileNames = (value["names"] as? List<*>)?.map { it.toString() } ?: emptyList()
                    ControlValue.FileValue(fileIds, fileNames)
                } else {
                    ControlValue.EmptyValue
                }
            }

            is Button -> ControlValue.EmptyValue
        }
    }
}

private fun defaultValidatorsFor(form: Form): Map<String, List<FormValidator>> {
    val validators = mutableMapOf<String, List<FormValidator>>()

    form.controls.forEach { control ->
        val controlFormValidators = mutableListOf<FormValidator>()
        if (control is RequirableControl && control.required) {
            controlFormValidators += RequiredFormValidator()
        }
        when (control) {
            is TextField -> {
                if (control.validationPattern != null && control.validationMessage != null) {
                    controlFormValidators.add(
                        PatternFormValidator(
                            control.validationPattern,
                            control.validationMessage
                        )
                    )
                }
            }

            is Dropdown -> {
                controlFormValidators.add(
                    DropDownFormValidator(
                        control.options.map { it.value },
                        "Value must be one of ${control.options.joinToString()}",
                    )
                )
            }

            is DatePicker -> {
            }

            is TimePicker -> {
            }

            is FileUpload -> {
            }

            is Slider -> {
                controlFormValidators.add(
                    DoubleRangeFormValidator(
                        control.min,
                        control.max,
                        "Value must be between ${control.min} and ${control.max}",
                    )
                )

            }

            else -> {} // No validation for other controls
        }

        if (controlFormValidators.isNotEmpty()) {
            validators[control.id] = controlFormValidators
        }
    }
    return validators
}
