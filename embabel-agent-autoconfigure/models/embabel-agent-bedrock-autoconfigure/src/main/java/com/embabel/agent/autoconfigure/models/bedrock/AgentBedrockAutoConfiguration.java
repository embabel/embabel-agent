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

import com.embabel.agent.config.models.bedrock.BedrockModelsConfig;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for AWS Bedrock models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Imports the [BedrockModelsConfig] configuration to dynamically register Bedrock model beans
 *
 * <p>Also exposes a default {@link ToolCallingManager} bean. Spring AI 2.0's
 * {@code BedrockConverseProxyChatAutoConfiguration#bedrockProxyChatModel(...)} factory method
 * autowires a {@code ToolCallingManager} (parameter 4), but Spring AI 2.0 ships no auto-config
 * that creates one — embabel factories build them inline per model. We register a primary
 * shared {@code ToolCallingManager} here (gated with {@link ConditionalOnMissingBean}) so the
 * Spring AI Bedrock proxy-chat auto-config can wire up alongside embabel's own model beans
 * without crashing context refresh.
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import(BedrockModelsConfig.class)
public class AgentBedrockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolCallingManager toolCallingManager(ObjectProvider<ObservationRegistry> observationRegistry) {
        return ToolCallingManager.builder()
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }
}
