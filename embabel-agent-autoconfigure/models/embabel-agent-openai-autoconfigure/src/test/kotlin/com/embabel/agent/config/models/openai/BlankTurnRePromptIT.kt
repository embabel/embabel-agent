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

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.test.loop.BlankTurnRePromptTestSupport
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * [BlankTurnRePromptTestSupport] against the real OpenAI API. OpenAI takes a blank assistant turn,
 * so this guards the history the loop sends after the fix rather than the fix itself.
 */
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY and makes a real call to api.openai.com",
)
class BlankTurnRePromptIT : BlankTurnRePromptTestSupport(OpenAiModels.GPT_41_MINI) {

    override fun withContext(check: (ApplicationContext) -> Unit) {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentOpenAiAutoConfiguration::class.java))
            .withPropertyValues(
                // One attempt, so a rejected re-prompt fails fast instead of sitting out the backoff.
                "embabel.agent.platform.models.openai.max-attempts=1",
            )
            .run { context -> check(context) }
    }
}
