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
package com.embabel.coding.tools.jvm

import com.embabel.agent.api.common.PromptRunner
import com.embabel.coding.tools.api.ApiReference
import junit.framework.TestCase.assertFalse
import org.junit.jupiter.api.Test

class ClassGraphApiReferenceExtractorTest {

    @Test
    fun `extract Embabel agent framework`() {
        val cigar = ClassGraphApiReferenceExtractor()
        val apiref = cigar.fromProjectClasspath(
            name = "test",
            acceptedPackages = setOf("com.embabel.agent"),
        )
        val tools = ApiReference(apiref)
        val pr = tools.findClassSignature(PromptRunner::class.java.name)
        assertFalse(pr.isEmpty())
        val agp = tools.findPackageSignature("com.embabel.agent.api.common")
        assertFalse(agp.isEmpty())
    }

}
