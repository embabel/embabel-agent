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
package com.embabel.coding.tools.api

import com.embabel.common.core.types.Named

data class ApiMethod(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val annotations: List<String> = emptyList(),
    val comment: String? = null,
)

data class ApiClass(
    val name: String,
    val packageName: String,
    val type: String, // class, interface, enum, annotation
    val methods: List<ApiMethod> = emptyList(),
    val annotations: List<String> = emptyList(),
    val superTypes: List<String> = emptyList(),
    val comment: String? = null,
) {

    fun fqn() = "$packageName.$name"
}

/**
 * Representation of an API with its classes and methods.
 */
data class Api(
    override val name: String,
    val classes: List<ApiClass>,
    val totalClasses: Int,
    val totalMethods: Int,
) : Named
