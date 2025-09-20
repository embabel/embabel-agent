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
package com.embabel.agent.test.models

import com.embabel.common.ai.model.OptionsConverter
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions

abstract class OptionsConverterTestSupport<O : ChatOptions>(
    protected val optionsConverter: OptionsConverter<O>
) {

    @Test
    fun `should preserve core values`() {
        checkOptionsConverterPreservesCoreValues(optionsConverter)
    }
}
