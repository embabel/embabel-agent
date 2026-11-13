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
package com.embabel.agent.config.models.cloudflare

import com.embabel.agent.systemone.SystemOneEndpoint
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Settings for the Cloudflare CLEF models, bound beneath [PREFIX]. The models themselves come from
 * the starter's catalog, `models/cloudflare-models.yml`.
 *
 * @property apiKey the API key (a Cloudflare API token) to use when `CLOUDFLARE_API_TOKEN` is absent or blank
 * @property accountId the account that runs the models, used when `CLOUDFLARE_ACCOUNT_ID` is absent or blank
 * @property maxResponseBytes the largest response body that is read, 1 MiB by default
 */
@ConfigurationProperties(CloudflareProperties.PREFIX)
class CloudflareProperties(
    val apiKey: String? = null,
    val accountId: String? = null,
    val maxResponseBytes: Int = SystemOneEndpoint.DEFAULT_MAX_RESPONSE_BYTES,
) {

    override fun toString(): String =
        "CloudflareProperties(apiKey=[REDACTED], accountId=${if (accountId.isNullOrBlank()) "[UNSET]" else "[CONFIGURED]"}, " +
            "maxResponseBytes=$maxResponseBytes)"

    companion object {
        /** Where these settings live. */
        const val PREFIX = "embabel.agent.platform.models.cloudflare"
    }
}
