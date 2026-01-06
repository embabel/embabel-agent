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
package com.embabel.common.util

/**
 * Convenient utility interface for transforming strings.
 */
fun interface StringTransformer {

    fun transform(raw: String): String

    companion object {

        val IDENTITY: StringTransformer = StringTransformer { it }

        /**
         * Apply transformers in order
         */
        fun transform(raw: String, transformers: List<StringTransformer>): String {
            var transformedContent = raw

            // Run all transformers
            for (transformer in transformers) {
                transformedContent = transformer.transform(transformedContent)
            }
            return transformedContent
        }

        operator fun invoke(vararg transformers: StringTransformer): StringTransformer {
            return StringTransformer { raw -> transform(raw, transformers.toList()) }
        }
    }
}
