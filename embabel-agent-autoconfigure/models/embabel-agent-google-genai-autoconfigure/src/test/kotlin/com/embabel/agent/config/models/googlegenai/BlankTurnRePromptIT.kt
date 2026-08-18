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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.agent.test.loop.BlankTurnRePromptTestSupport
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * [BlankTurnRePromptTestSupport] against the real Google API, in Vertex mode: only Vertex rejects a
 * blank assistant turn, the API-key endpoint (`generativelanguage.googleapis.com`) accepts it. So
 * this fails if the fix is reverted.
 *
 * Vertex also needs application default credentials, and no environment variable reports whether
 * those are valid — `gcloud auth application-default login` writes them to a file. `GOOGLE_PROJECT_ID`
 * alone would let the bulk run start the test on a machine whose credentials have expired, and it
 * would fail rather than skip. So the run is opt-in, the way `OnnxEmbeddingServiceIT` is.
 */
@EnabledIfEnvironmentVariable(
    named = "EMBABEL_RUN_VERTEX_INTEGRATION_TESTS",
    matches = ".+",
    disabledReason = "Integration test needs valid Vertex AI application default credentials",
)
@EnabledIfEnvironmentVariable(
    named = "GOOGLE_PROJECT_ID",
    matches = ".+",
    disabledReason = "Integration test requires GOOGLE_PROJECT_ID",
)
class BlankTurnRePromptIT : BlankTurnRePromptTestSupport(GoogleGenAiModels.GEMINI_2_5_FLASH) {

    override fun withContext(check: (ApplicationContext) -> Unit) {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentGoogleGenAiAutoConfiguration::class.java))
            .withPropertyValues(
                // Vertex mode: this is where a blank assistant turn is rejected.
                "embabel.agent.platform.models.googlegenai.project-id=\${GOOGLE_PROJECT_ID}",
                "embabel.agent.platform.models.googlegenai.location=us-central1",
                // One attempt, so a rejected re-prompt fails fast instead of sitting out the backoff.
                "embabel.agent.platform.models.googlegenai.max-attempts=1",
            )
            .run { context -> check(context) }
    }
}
