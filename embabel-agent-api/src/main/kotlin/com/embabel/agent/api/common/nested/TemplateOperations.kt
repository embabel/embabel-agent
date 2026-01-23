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

import com.embabel.agent.api.common.PromptRunner

/**
 * Llm operations based on a compiled template.
 * Similar to [com.embabel.agent.api.common.PromptRunnerOperations], but taking a model instead of a template string.
 */
@Deprecated(
    message = "Use PromptRunner.Rendering instead",
    replaceWith = ReplaceWith(
        expression = "PromptRunner.Rendering",
        imports = arrayOf("com.embabel.agent.api.common.PromptRunner.Rendering"),
    )
)
interface TemplateOperations : PromptRunner.Rendering
