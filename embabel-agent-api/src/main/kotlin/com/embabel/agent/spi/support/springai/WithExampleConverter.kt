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
package com.embabel.agent.spi.support.springai

import com.embabel.common.util.DummyInstanceCreator
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.ai.converter.StructuredOutputConverter

/**
 * Decorator for Spring's [StructuredOutputConverter] that adds few-shot examples to the output format description.
 *
 * <p>
 * Few-shot examples are often used in prompt engineering to help AI models understand the expected output format
 * by providing concrete example outputs. This converter generates such examples automatically using dummy data
 * and injects them into the format description returned by [getFormat()].
 *
 * @param T the output type for the converter
 * @param delegate the underlying output converter to which conversion is delegated
 * @param outputClass the class type for which dummy example instances will be generated
 * @param ifPossible determines whether to include both success and failure examples (true) or just a simple example (false)
 * @param generateExamples whether to generate examples or not. This class does nothing if it is false
 * Wraps an existing StructuredOutputConverter with this class to enhance its format description for LLM prompting.
 */
class WithExampleConverter<T>(
    private val delegate: StructuredOutputConverter<T>,
    private val outputClass: Class<T>,
    private val ifPossible: Boolean,
    private val generateExamples: Boolean,
) : StructuredOutputConverter<T> {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    /**
     * Delegates conversion to the underlying [delegate].
     *
     * @param source the raw output string to convert
     * @return the converted output, or null if conversion fails
     */
    override fun convert(source: String): T? = delegate.convert(source)

    /**
     * Returns a format description string, augmented with few-shot examples.
     *
     * The example(s) are generated using [DummyInstanceCreator], which creates a mock instance of [outputClass].
     *
     * If [ifPossible] is true, the example is wrapped in a structure (presumably [MaybeReturn]) that shows both
     * 'success' and 'failure' cases. This is helpful when the output type can be a success or an error.
     *
     * If [ifPossible] is false, only a single example output is shown (not wrapped).
     *
     * The underlying converter's format is always appended after the examples.
     *
     * @return a string describing the output format, including examples
     */
    override fun getFormat(): String {
        if (!generateExamples) {
            // If example generation is disabled, return the delegate's format directly
            return delegate.format
        }

        val outputClassToUse: Class<*> = when {
            // Look for a deserialization annotation
            outputClass.isInterface -> {
                outputClass.getAnnotation(JsonDeserialize::class.java)?.`as`?.java
                    ?: error(
                        "An interface used for prompt return needs deserialization information: $outputClass"
                    )
            }

            else -> outputClass
        }

        // Generate a dummy example instance of the output type using lorem ipsum values for strings
        // The output is always a dummy instance of the most specific output class, even if it was a interface
        val example = DummyInstanceCreator.LoremIpsum.createDummyInstance(outputClassToUse)
        return if (ifPossible) {
            // If possible, show both a success and a failure example using a wrapper structure.
            // The MaybeReturn class is assumed to be a generic wrapper for success/failure outputs.
            // - success: wraps the dummy example
            // - failure: wraps a fixed failure message
            """|
               |Examples:
               |   success:
               |   ${objectMapper.writeValueAsString(MaybeReturn(success = example))}
               |
               |   failure:
               |   ${objectMapper.writeValueAsString(MaybeReturn<T>(failure = "Insufficient context to create this structure"))}
               |
               |${delegate.format}
               |""".trimMargin()
        } else {
            // Otherwise, just show a single example output (not wrapped in MaybeReturn)
            """|
               |Example:
               |${objectMapper.writeValueAsString(example)}
               |
               |${delegate.format}
               |""".trimMargin()
        }
    }
}
