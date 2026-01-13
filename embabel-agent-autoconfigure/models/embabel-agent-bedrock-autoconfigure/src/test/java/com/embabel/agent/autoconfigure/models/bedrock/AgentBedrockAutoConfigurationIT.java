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
package com.embabel.agent.autoconfigure.models.bedrock;

import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Arrays;
import java.util.List;

import static com.embabel.agent.config.models.bedrock.BedrockModelsConfig.EU_ANTHROPIC_CLAUDE_OPUS_4;
import static com.embabel.agent.config.models.bedrock.BedrockModelsConfig.EU_ANTHROPIC_CLAUDE_SONNET_4;

@SpringBootTest(
        classes = AgentBedrockAutoConfigurationIT.class,
        properties = {
                "embabel.models.default-llm=" + EU_ANTHROPIC_CLAUDE_SONNET_4,
                "embabel.models.llms.cheapest=" + EU_ANTHROPIC_CLAUDE_SONNET_4,
                "embabel.models.llms.best=" + EU_ANTHROPIC_CLAUDE_OPUS_4,
                "spring.ai.bedrock.aws.region=eu-west-3",
                "spring.ai.bedrock.aws.access-key=AWSACCESSKEYID",
                "spring.ai.bedrock.aws.secret-key=AWSSECRETACCESSKEY"
        })
@ComponentScan(basePackages = "com.embabel.agent.autoconfigure")
@ImportAutoConfiguration(classes = {AgentBedrockAutoConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentBedrockAutoConfigurationIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    public void testAutoConfiguredBedrockModelsBeanPresence() {
        List<String> bedrockLlmsNames = Arrays.stream(applicationContext.getBeanNamesForType(LlmService.class))
                .filter(it -> it.startsWith("bedrockModel-"))
                .map(it -> applicationContext.getBean(it, LlmService.class))
                .map(LlmService::getName)
                .toList();

        Assertions.assertFalse(bedrockLlmsNames.isEmpty());
    }

}