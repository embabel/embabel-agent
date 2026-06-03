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
package com.embabel.agent.autoconfigure.models.ocigenai;

import com.embabel.agent.config.models.ocigenai.OciGenAiClientConfig;
import com.embabel.agent.config.models.ocigenai.OciGenAiModelsConfig;
import com.oracle.bmc.generativeaiinference.GenerativeAiInference;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for OCI Generative AI models in the Embabel Agent system.
 */
@AutoConfiguration
@ConditionalOnClass(GenerativeAiInference.class)
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import({OciGenAiClientConfig.class, OciGenAiModelsConfig.class})
public class AgentOciGenAiAutoConfiguration {
}
