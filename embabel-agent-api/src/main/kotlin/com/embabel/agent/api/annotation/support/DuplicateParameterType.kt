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
package com.embabel.agent.api.annotation.support

import java.lang.reflect.Method
import java.lang.reflect.Parameter

/**
 * A class representing a method with multiple parameters of the same type that have not been annotated with
 * [com.embabel.agent.api.annotation.RequireNameMatch].
 */
class DuplicateParameterType(
    /** The method with multiple parameters of the same type which require @RequireNameMatch annotation */
    val method: Method,
    /** The type of the parameter that is ambiguous. */
    val conflictingClassType: Class<*>,
    /** The parameters that are ambiguous. */
    val conflictingParameters: List<Parameter>,
)
