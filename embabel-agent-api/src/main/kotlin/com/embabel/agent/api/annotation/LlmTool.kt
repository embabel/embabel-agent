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

/**
 * Marks a method as a tool that can be invoked by an LLM.
 * Use with [com.embabel.agent.api.tool.Tool.Companion.fromInstance] or [com.embabel.agent.api.tool.Tool.Companion.fromMethod] to create Tool instances.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmTool(

    /**
     * Description of what the tool does. Used by LLM to decide when to call it.
     */
    val description: String = "",

    /**
     * Tool name. Defaults to method name if empty.
     */
    val name: String = "",


    /**
     * Whether to return the result directly without further LLM processing.
     */
    val returnDirect: Boolean = false,
) {

    /**
     * Describes a tool parameter. Apply to method parameters.
     * Parameters don't need to be annotated: If they are,
     * the purpose is to provide description and required information.
     */
    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Param(

        /**
         * Description of the parameter. Used by LLM to understand what value to provide.
         */
        val description: String,

        /**
         * Whether this parameter is required. Defaults to true.
         * For optional parameters, the method parameter should have a default value.
         */
        val required: Boolean = true,
    )
}
