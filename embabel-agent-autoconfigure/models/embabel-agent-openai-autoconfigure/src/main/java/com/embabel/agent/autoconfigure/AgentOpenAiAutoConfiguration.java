/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.autoconfigure;

import com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration;
import com.embabel.agent.config.models.OpenAiModels;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@ConditionalOnClass({
        OpenAiModels.class})
@Import(OpenAiModels.class)
public class AgentOpenAiAutoConfiguration {
}
