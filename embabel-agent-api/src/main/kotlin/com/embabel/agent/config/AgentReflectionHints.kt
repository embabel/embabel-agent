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
package com.embabel.agent.config

import com.embabel.agent.domain.io.UserInput
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.MemberCategory
import org.springframework.context.annotation.ImportRuntimeHints

class AgentReflectionHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(UserInput::class.java) {
            it.withMembers(
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_PUBLIC_METHODS
            )
        }
    }
}
