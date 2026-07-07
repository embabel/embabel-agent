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
package com.embabel.agent.api.models

/**
 * Provides constants for Mistral AI model identifiers.
 * This class contains the latest model versions for models offered by Mistral AI.
 */
class MistralAiModels {

	companion object {

        const val PROVIDER = "Mistral AI"

		const val MISTRAL_MEDIUM_31 = "mistral-medium-2508"

        const val MISTRAL_MEDIUM_35 = "mistral-medium-2604"

        const val MISTRAL_SMALL_32 = "mistral-small-2506"

        const val MINISTRAL_8B = "ministral-8b-2410"

        const val MINISTRAL_3B = "ministral-3b-2410"

        const val CODESTRAL = "codestral-2508"

        @Deprecated(
            message = "devstral-medium-2507 (Devstral Medium 1.0) was retired by Mistral on 2026-05-31 and now returns 400 (invalid model). Use DEVSTRAL_2.",
            replaceWith = ReplaceWith("DEVSTRAL_2")
        )
        const val DEVSTRAL_MEDIUM_10 = "devstral-medium-2507"

        @Deprecated(
            message = "devstral-small-2507 (Devstral Small 1.1) was retired by Mistral on 2026-05-31 and now returns 400 (invalid model). Use DEVSTRAL_2.",
            replaceWith = ReplaceWith("DEVSTRAL_2")
        )
        const val DEVSTRAL_SMALL_11 = "devstral-small-2507"

        @Deprecated(
            message = "mistral-large-2411 (Mistral Large 2.1) was retired by Mistral on 2026-05-31 and now returns 400 (invalid model). Use MISTRAL_LARGE_3.",
            replaceWith = ReplaceWith("MISTRAL_LARGE_3")
        )
        const val MISTRAL_LARGE_21 = "mistral-large-2411"

        const val MISTRAL_LARGE_3 = "mistral-large-2512"

        const val MISTRAL_SMALL_4 = "mistral-small-2603"

        const val MAGISTRAL_MEDIUM_12 = "magistral-medium-2509"

        const val MAGISTRAL_SMALL_12 = "magistral-small-2509"

        const val MINISTRAL_3_14B = "ministral-14b-2512"

        const val MINISTRAL_3_8B = "ministral-8b-2512"

        const val MINISTRAL_3_3B = "ministral-3b-2512"

        const val DEVSTRAL_2 = "devstral-2512"
	}
}
