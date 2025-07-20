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
package com.embabel.agent.shell.config

import com.embabel.agent.shell.ShellCommands
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

class ShellRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(
            ShellCommands::class.java,
            { hint -> hint.withMembers() })
//        hints.reflection().registerType(ShellCommands::class.java, MemberCategory.INVOKE_DECLARED_METHODS)


        // Register reflection hints for main shell components
//        hints.reflection().registerType(ShellCommands::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
//        }
//
//        hints.reflection().registerType(ShellConfig::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
//        }

//        hints.reflection().registerType(ToolsStats::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
//        }
//
//        hints.reflection().registerType(ModelProvider::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
//        }

//        hints.reflection().registerType(ChatConfig::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
//        }
//
//        hints.reflection().registerType(ShellProperties::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
//        }
//
//        // Register KotlinModule for Jackson
//        hints.reflection().registerType(KotlinModule::class.java) { builder ->
//            builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
//        }
//
//        // Register ShellMethod annotation (used by Spring Shell)
//        hints.reflection().registerType(TypeReference.of("org.springframework.shell.standard.ShellMethod")) { builder ->
//            builder.withMembers()
//        }

        // Register annotation access
        hints.reflection().registerType(
            org.springframework.shell.standard.ShellMethod::class.java
        )
        hints.reflection().registerType(
            org.springframework.shell.standard.ShellComponent::class.java
        )

    }
}
