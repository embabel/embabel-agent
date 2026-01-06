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
package com.embabel.agent.model

import com.embabel.agent.spi.support.FakeChatModel
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.PricingModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("test")
class TestModels {

    @Bean
    fun testLlm(): Llm {
        return Llm(
            name = "test-llm",
            model = FakeChatModel("I am a fake chat model"),
            pricingModel = PricingModel.usdPer1MTokens(.1, .1),
            provider = "test",
            optionsConverter = DefaultOptionsConverter,
        )
    }
}
