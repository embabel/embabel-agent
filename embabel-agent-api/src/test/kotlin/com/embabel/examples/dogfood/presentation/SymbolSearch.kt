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
package com.embabel.examples.dogfood.presentation

import com.embabel.agent.tools.file.PatternSearch
import com.embabel.agent.tools.file.PatternSearch.PatternMatch


/**
 * Extends PatternSearch to provide specific methods for searching for symbols in code.
 */
interface SymbolSearch : PatternSearch {

    fun classPattern(className: String): Regex {
        return Regex(
            "(^|\\s)(class|interface|object|enum\\s+class|data\\s+class|sealed\\s+class|abstract\\s+class)\\s+" +
                    className +  // The exact class name
                    "\\b" +         // Word boundary to ensure it doesn't match partial names
                    "([<(]|\\s|$)"  // Followed by generic parameters, constructor params, whitespace or line end
        )
    }

    fun findClassInProject(
        className: String,
        globPattern: String = "**/*.{kt,java}",
        useParallelSearch: Boolean = true
    ): List<PatternMatch> {
        return findPatternInProject(
            pattern = classPattern(className),
            globPattern = globPattern,
            useParallelSearch = useParallelSearch
        )
    }
}
