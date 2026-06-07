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

import org.jetbrains.annotations.ApiStatus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@ApiStatus.Internal
public class OciGenAiEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DEFAULT_LLM_PROPERTY = "embabel.models.default-llm";
    static final String DEFAULT_EMBEDDING_PROPERTY = "embabel.models.default-embedding-model";
    static final String OCI_DEFAULT_LLM = "cohere.command-a-03-2025";
    static final String OCI_DEFAULT_EMBEDDING = "cohere.embed-v4.0";
    private static final String PROPERTY_SOURCE_NAME = "ociGenAiDefaultModels";
    private static final String OPENAI_AUTO_CONFIGURATION =
            "com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isOpenAiProviderPresent()) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        if (!environment.containsProperty(DEFAULT_LLM_PROPERTY)) {
            defaults.put(DEFAULT_LLM_PROPERTY, OCI_DEFAULT_LLM);
        }
        if (!environment.containsProperty(DEFAULT_EMBEDDING_PROPERTY)) {
            defaults.put(DEFAULT_EMBEDDING_PROPERTY, OCI_DEFAULT_EMBEDDING);
        }
        if (!defaults.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    boolean isOpenAiProviderPresent() {
        return ClassUtils.isPresent(OPENAI_AUTO_CONFIGURATION, getClass().getClassLoader());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
