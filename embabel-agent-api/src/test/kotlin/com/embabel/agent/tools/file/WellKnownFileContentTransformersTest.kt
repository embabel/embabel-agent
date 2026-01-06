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
package com.embabel.agent.tools.file

import com.embabel.common.util.StringTransformer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WellKnownFileContentTransformersTest {

    @Nested
    inner class ApacheLicenseHeader {

        @Test
        fun `should not strip`() {
            val notTheres = listOf(
                "Wake in Fright",
                "Walkabout",
                "The Cars That Ate Paris",
                "Max Max",
                "Picnic at Hanging Rock",
                "Dead Calm",
            )
            notTheres.forEach {
                assertEquals(it, WellKnownFileContentTransformers.removeApacheLicenseHeader.transform(it))
            }
        }

        @Test
        fun `should strip`() {
            val it = """
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
                class Foo
            """.trimIndent()
            assertEquals("class Foo", WellKnownFileContentTransformers.removeApacheLicenseHeader.transform(it))
        }
    }

    @Nested
    inner class AllSanitizers {

        @Test
        fun `should not strip`() {
            val notTheres = listOf(
                "Wake in Fright",
                "Walkabout",
                "The Cars That Ate Paris",
                "Max Max",
                "Picnic at Hanging Rock",
                "Dead Calm",
            )
            notTheres.forEach {
                assertEquals(
                    it,
                    StringTransformer.transform(it, WellKnownFileContentTransformers.allSanitizers())
                )
            }
        }

        @Test
        fun `should strip`() {
            val it = """
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
                class Foo
            """.trimIndent()
            assertEquals(
                "class Foo",
                StringTransformer.transform(it, WellKnownFileContentTransformers.allSanitizers())
            )
        }
    }

}
