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
import com.embabel.common.util.indent
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

/**
 * UX-independent form definition. Controls, not data.
 */
data class Form(
    val title: String,
    val controls: List<Control>,
    val id: String = UUID.randomUUID().toString(),
) : HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return toString().indent(indent)
    }
}

enum class ControlType {
    BUTTON, DROPDOWN, TEXT_FIELD, TEXT_AREA, CHECKBOX, RADIO_GROUP,
    DATE_PICKER, TIME_PICKER, SLIDER, TOGGLE, FILE_UPLOAD
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY
)
@JsonSubTypes(
    JsonSubTypes.Type(value = Button::class, name = "BUTTON"),
    JsonSubTypes.Type(value = Dropdown::class, name = "DROPDOWN"),
    JsonSubTypes.Type(value = TextField::class, name = "TEXT_FIELD"),
    JsonSubTypes.Type(value = TextArea::class, name = "TEXT_AREA"),
    JsonSubTypes.Type(value = Checkbox::class, name = "CHECKBOX"),
    JsonSubTypes.Type(value = RadioGroup::class, name = "RADIO_GROUP"),
    JsonSubTypes.Type(value = DatePicker::class, name = "DATE_PICKER"),
    JsonSubTypes.Type(value = TimePicker::class, name = "TIME_PICKER"),
    JsonSubTypes.Type(value = Slider::class, name = "SLIDER"),
    JsonSubTypes.Type(value = Toggle::class, name = "TOGGLE"),
    JsonSubTypes.Type(value = FileUpload::class, name = "FILE_UPLOAD"),
)
sealed interface Control {
    val id: String
    val type: ControlType
}

sealed interface RequirableControl : Control {
    val required: Boolean
    val disabled: Boolean
}

data class Button(
    val label: String,
    val description: String = label,
    override val id: String = UUID.randomUUID().toString(),
) : Control {

    override val type: ControlType = ControlType.BUTTON
}

data class Dropdown(
    val label: String,
    val options: List<DropdownOption>,
    val placeholder: String = "",
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {
    override val type: ControlType = ControlType.DROPDOWN
}

data class DropdownOption(val label: String, val value: String = label)

data class TextField(
    val label: String,
    val placeholder: String = "",
    val value: String = "",
    val maxLength: Int? = null,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    val validationPattern: String? = null,
    val validationMessage: String? = null,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.TEXT_FIELD
}

data class TextArea(
    val label: String,
    val placeholder: String = "",
    val value: String = "",
    val rows: Int = 3,
    val maxLength: Int? = null,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.TEXT_AREA
}

data class Checkbox(
    val label: String,
    val checked: Boolean = false,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {
    override val type: ControlType = ControlType.CHECKBOX

}

data class RadioGroup(
    val label: String,
    val options: List<RadioOption>,
    val selectedValue: String? = null,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.RADIO_GROUP
}

data class RadioOption(val label: String, val value: String = label)

data class DatePicker(
    val label: String,
    val value: String? = null,
    val minDate: String? = null,
    val maxDate: String? = null,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.DATE_PICKER
}

data class TimePicker(
    val label: String,
    val value: String? = null,
    val is24Hour: Boolean = false,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.TIME_PICKER
}

data class Slider(
    val label: String,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val step: Double = 1.0,
    val value: Double = min,
    val showMarkers: Boolean = false,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.SLIDER
}

data class Toggle(
    val label: String,
    val enabled: Boolean = false,
    val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : Control {

    override val type: ControlType = ControlType.TOGGLE
}

data class FileUpload(
    val label: String,
    val acceptedFileTypes: List<String> = listOf("*/*"),
    val maxFileSize: Long? = null,
    val maxFiles: Int = 1,
    override val required: Boolean = true,
    override val disabled: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : RequirableControl {

    override val type: ControlType = ControlType.FILE_UPLOAD
}
