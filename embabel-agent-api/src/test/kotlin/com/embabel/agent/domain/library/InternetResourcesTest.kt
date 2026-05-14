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
package com.embabel.agent.domain.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies the prompt-facing formatting behavior of [InternetResource] and [InternetResources].
 */
class InternetResourcesTest {

    /**
     * Confirms that a single resource formats its prompt contribution as URL and summary lines.
     */
    @Test
    fun `internet resource contribution includes url and summary`() {
        // Arrange
        val resource = InternetResource(
            url = "https://example.com/article",
            summary = "Concise article summary"
        )

        // Assert
        assertThat(resource.contribution()).isEqualTo(
            """
            URL: https://example.com/article
            Summary: Concise article summary
            """.trimIndent()
        )
    }

    /**
     * Confirms that multiple resources are joined into one prompt contribution with one block per link.
     */
    @Test
    fun `internet resources contribution joins link contributions`() {
        // Arrange
        val resources = TestInternetResources(
            links = listOf(
                InternetResource(
                    url = "https://example.com/one",
                    summary = "First summary"
                ),
                InternetResource(
                    url = "https://example.com/two",
                    summary = "Second summary"
                )
            )
        )

        // Assert
        assertThat(resources.contribution()).isEqualTo(
            """
            URL: https://example.com/one
            Summary: First summary
            URL: https://example.com/two
            Summary: Second summary
            """.trimIndent()
        )
    }

    /**
     * Confirms that the contribution falls back to a readable message when no links are present.
     */
    @Test
    fun `internet resources contribution uses fallback when links are empty`() {
        // Arrange
        val resources = TestInternetResources(emptyList())

        // Assert
        assertThat(resources.contribution()).isEqualTo("No relevant internet resources found.")
    }

    /**
     * Confirms the custom string representation exposed by [InternetResource].
     */
    @Test
    fun `internet resource to string includes url and summary`() {
        // Arrange
        val resource = InternetResource(
            url = "https://example.com/article",
            summary = "Concise article summary"
        )

        // Assert
        assertThat(resource.toString()).isEqualTo(
            "InternetResource(url='https://example.com/article', summary='Concise article summary')"
        )
    }

    /**
     * Minimal implementation used to exercise the default [InternetResources.contribution] behavior.
     */
    private data class TestInternetResources(
        override val links: List<InternetResource>
    ) : InternetResources
}
