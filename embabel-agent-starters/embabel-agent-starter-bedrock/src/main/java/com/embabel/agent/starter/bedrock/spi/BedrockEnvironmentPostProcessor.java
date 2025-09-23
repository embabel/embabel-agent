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
package com.embabel.agent.starter.bedrock.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * An {@link org.springframework.boot.env.EnvironmentPostProcessor} to customize the environment
 * for Bedrock-related configurations.
 * <p>
 * This class can be used to programmatically modify the application's environment before the
 * application context is refreshed. It is particularly useful for setting up properties or
 * profiles related to Bedrock services.
 */
public class BedrockEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(BedrockEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        //This is an interim solution to activate the bedrock profile when application-bedrock.yml is present.
        //A better solution would be to shift responsibility to the user external configuration managed directly
        //by the user
        logger.debug("Bedrock Models detected - applying Bedrock environment configuration");
        environment.addActiveProfile("bedrock");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // Run early, but after core Spring Boot processors
    }
}
