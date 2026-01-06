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

import kotlin.reflect.KClass

/**
 * Annotation for a text field
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Text(
    val label: String,
    val placeholder: String = "",
)

/**
 * Generate forms from JVM types.
 */
interface FormGenerator {

    fun <T : Any> generateForm(
        dataClass: KClass<T>,
        title: String,
    ): Form

    fun <T : Any> generateForm(
        dataClass: Class<T>,
        title: String,
    ): Form =
        generateForm(dataClass.kotlin, title)

}

/**
 * Generate a form from any class with FormField annotations
 */
inline fun <reified T : Any> FormGenerator.generateForm(title: String): Form {
    return generateForm(T::class, title)
}
