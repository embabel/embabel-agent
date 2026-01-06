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
package com.embabel.agent.eval.config

import org.springframework.stereotype.Service
import javax.script.ScriptEngineManager
import javax.script.SimpleBindings
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

// This is your script definition
@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = AppScriptCompilationConfiguration::class // Make sure this is a compilation config
)
class AppScript(val context: Map<String, Any>)

// Compilation configuration (used in the KotlinScript annotation)
object AppScriptCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})

@Service
class ScriptEvaluationService {
    private val engine = ScriptEngineManager().getEngineByExtension("kts")

    init {
        if (engine == null) {
            throw IllegalStateException("Kotlin script engine not found. Make sure kotlin-scripting-jsr223 is in your classpath.")
        }
    }

    fun <T> evaluateExpression(expression: String, context: Map<String, Any>): T? {
        val bindings = SimpleBindings(context.toMutableMap())
        return try {
            @Suppress("UNCHECKED_CAST")
            engine.eval(expression, bindings) as T
        } catch (e: Exception) {
            println("Script evaluation error: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
