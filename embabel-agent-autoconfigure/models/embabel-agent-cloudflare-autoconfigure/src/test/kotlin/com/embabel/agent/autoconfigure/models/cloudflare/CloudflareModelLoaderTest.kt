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
package com.embabel.agent.autoconfigure.models.cloudflare

import com.embabel.agent.config.models.cloudflare.CloudflareModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.DefaultResourceLoader

class CloudflareModelLoaderTest {

    @Nested
    inner class Catalog {

        @Test
        fun `the starter catalog defines every Workers AI decision model`() {
            val models = CloudflareModelLoader().loadAutoConfigMetadata().models
            assertEquals(listOf("clef", "clef-flash", "clef-omni", "cloudflare-jev"), models.map { it.name })
            assertEquals(
                listOf("@cf/cloudflare/clef", "@cf/cloudflare/clef-flash", "@cf/cloudflare/clef-omni", "typesafe/jev"),
                models.map { it.modelId },
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["duplicate", "malformed", "empty", "missing"])
        fun `an unusable catalog fails naming the resource`(catalog: String) {
            val path = "classpath:catalogs/$catalog.yml"
            val e = assertThrows<IllegalStateException> { CloudflareModelLoader(DefaultResourceLoader(), path).loadAutoConfigMetadata() }
            assertTrue(e.message!!.contains(path), e.message)
        }

        @Test
        fun `a duplicate name failure names the repeated names`() {
            val loader = CloudflareModelLoader(DefaultResourceLoader(), "classpath:catalogs/duplicate.yml")
            val e = assertThrows<IllegalStateException> { loader.loadAutoConfigMetadata() }
            assertTrue(e.message!!.contains("Repeated names: [clef-a]"), e.message)
        }
    }
}
