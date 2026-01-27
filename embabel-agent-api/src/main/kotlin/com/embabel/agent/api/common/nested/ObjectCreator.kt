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
package com.embabel.agent.api.common.nested

import com.embabel.agent.api.common.CreationExample
import com.embabel.agent.api.common.PromptRunner

/**
 * An example of creating an object of the given type.
 * Used to provide strongly typed examples to the ObjectCreator.
 * @param T the type of object to create
 * @param description description of the example--e.g. "good example, correct amount of detail"
 * @param value the example object
 */
@Deprecated(
    message = "Use CreationExample instead",
    replaceWith = ReplaceWith(
        expression = "CreationExample(description, value)",
        imports = arrayOf("com.embabel.agent.api.common.CreationExample"),
    )
)
class ObjectCreationExample<T>(
    description: String,
    value: T,
) : CreationExample<T>(
    description = description,
    value = value
)

/**
 * Interface to create objects of the given type from a prompt or messages.
 * Allows setting strongly typed examples.
 */
@Deprecated(
    message = "Use PromptRunner.Creating instead",
    replaceWith = ReplaceWith(
        expression = "PromptRunner.Creating<T>",
        imports = arrayOf("com.embabel.agent.api.common.PromptRunner.Creating"),
    )
)
interface ObjectCreator<T> : PromptRunner.Creating<T>
