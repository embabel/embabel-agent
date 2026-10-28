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
package com.embabel.agent.config.models.openai

import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.byok.InvalidApiKeyException
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Gateway-level PowerTokens checks that do not need Embabel's Spring context.
 *
 * Kept out of [PowerTokensCatalogIT] so GET /v1/models and the BYOK 401 probe stay fast
 * and cannot fail because of an unrelated test [GuardRailConfiguration] on the classpath.
 *
 * @see PowerTokensCatalogIT for chat / tool coverage through Embabel [com.embabel.agent.api.common.Ai]
 */
class PowerTokensModelsListIT {

    /** Not a key PowerTokens issued. */
    private val unknownKey = "not-a-real-key"

    /**
     * One GET /v1/models covers every smoke id. Built on a bare client on purpose: same reason
     * as OpenAiCatalogIT — this asks what PowerTokens serves, not what Embabel registered.
     */
    @Test
    @EnabledIfEnvironmentVariable(
        named = "POWERTOKENS_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires POWERTOKENS_API_KEY",
    )
    fun `every smoke model id is one PowerTokens serves`() {
        val client = OpenAIOkHttpClient.builder()
            .apiKey(System.getenv("POWERTOKENS_API_KEY"))
            .baseUrl(PowerTokensCatalogIT.BASE_URL)
            .build()
        val served = client.models().list().autoPager().map { it.id() }.toSet()

        val unknown = PowerTokensCatalogIT.smokeModels().filterNot { it in served }

        assertTrue(
            unknown.isEmpty(),
            "Smoke ids PowerTokens does not serve for this key. Either a typo, a retired model, " +
                "or one this account is not entitled to: $unknown",
        )
    }

    /**
     * Invalid-key probe via Embabel BYOK ([OpenAiCompatibleModelFactory.byok]), not the bare
     * openai-java client. Needs network; does not need POWERTOKENS_API_KEY or Spring.
     */
    @Test
    fun `powerTokens responds 401 to a key it did not issue`() {
        val ex = assertThrows<InvalidApiKeyException> {
            OpenAiCompatibleModelFactory.byok(
                baseUrl = PowerTokensCatalogIT.BASE_URL,
                apiKey = unknownKey,
                validationModel = PowerTokensCatalogIT.smokeModels().first(),
                validationProvider = "PowerTokens",
            ).buildValidated()
        }
        assertEquals(401, ex.statusCode)
    }
}
