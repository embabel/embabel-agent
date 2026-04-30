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
package com.embabel.agent.logging.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Collections;

/**
 * Environment post-processor that automatically configures logging for Embabel Agent library.
 * <p>
 * This processor loads the library's default logging configuration ({@code logback-embabel.xml})
 * unless the user has provided their own logging configuration via:
 * <ul>
 *   <li>{@code logging.config} property</li>
 *   <li>{@code logback-spring.xml} file in their application</li>
 * </ul>
 * <p>
 * The processor reads {@code embabel.agent.platform.logging.config} directly from the
 * {@code agent-platform.properties} file and adds it to the Spring Environment as
 * {@code logging.config} for Spring Boot's logging system to use during initialization.
 * <p>
 * <b>Separation of Concerns:</b> This processor only reads the properties file to obtain
 * the logging configuration path. It does NOT load properties into the Spring Environment.
 * The {@code AgentPlatformPropertiesLoader} (in embabel-agent-api) is responsible for loading
 * {@code agent-platform.properties} into the Environment for {@code @ConfigurationProperties} binding.
 *
 * @see EnvironmentPostProcessor
 * @see com.embabel.agent.spi.config.spring.AgentPlatformPropertiesLoader
 */
public class AgentLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(AgentLoggingEnvironmentPostProcessor.class);

    private static final String LOGGING_CONFIG_PROPERTY = "logging.config";
    private static final String LOGBACK_SPRING_XML = "logback-spring.xml";
    private static final String AGENT_PLATFORM_PROPERTIES = "agent-platform.properties";
    private static final String EMBABEL_LOGGING_CONFIG_PROPERTY = "embabel.agent.platform.logging.config";

    /**
     * Post-processes the environment to configure library logging if not already configured by the user.
     * <p>
     * Processing steps:
     * <ol>
     *   <li>Check if user has set {@code logging.config} property - if yes, skip</li>
     *   <li>Check if user has {@code logback-spring.xml} in classpath - if yes, skip</li>
     *   <li>Read {@code embabel.agent.platform.logging.config} from {@code agent-platform.properties}</li>
     *   <li>Set {@code logging.config} system property</li>
     * </ol>
     * <p>
     * <b>Note:</b> This processor reads {@code agent-platform.properties} as a simple resource file
     * without loading it into the Spring Environment. The {@code AgentPlatformPropertiesLoader}
     * (in embabel-agent-api) is responsible for loading properties into the Environment for
     * {@code @ConfigurationProperties} binding.
     *
     * @param environment the Spring environment
     * @param application the Spring application
     */
    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {

        log.debug("AgentLoggingEnvironmentPostProcessor invoked");

        // 1. If user explicitly sets logging.config → do nothing
        if (environment.containsProperty(LOGGING_CONFIG_PROPERTY)) {
            log.debug("User-defined logging.config detected — skipping library logging");
            return;
        }

        // 2. If user provides their own logback-spring.xml → do nothing
        if (resourceExists(LOGBACK_SPRING_XML)) {
            log.debug("Application logback-spring.xml detected — skipping library logging");
            return;
        }

        // 3. Read logging config path from agent-platform.properties file
        String loggingConfigPath = readLoggingConfigFromProperties();

        if (loggingConfigPath != null && !loggingConfigPath.isBlank()) {
            log.debug("Setting logging.config to {}", loggingConfigPath);
            // Add to Environment with highest priority so Spring Boot's LoggingApplicationListener can read it
            environment.getPropertySources().addFirst(
                    new MapPropertySource("loggingConfigSource",
                            Collections.singletonMap(LOGGING_CONFIG_PROPERTY, loggingConfigPath)));
        } else {
            log.warn("{} not found in {} — library logging disabled",
                    EMBABEL_LOGGING_CONFIG_PROPERTY, AGENT_PLATFORM_PROPERTIES);
        }
    }

    /**
     * Reads the logging configuration path from {@code agent-platform.properties} file.
     * <p>
     * This method reads the properties file as a simple resource without loading it into
     * the Spring Environment. The {@code AgentPlatformPropertiesLoader} (in embabel-agent-api)
     * handles loading properties into the Environment for {@code @ConfigurationProperties} binding.
     *
     * @return the logging config path from {@code embabel.agent.platform.logging.config}, or null if not found
     */
    private String readLoggingConfigFromProperties() {
        Resource resource = new ClassPathResource(AGENT_PLATFORM_PROPERTIES);
        if (!resource.exists()) {
            log.warn("{} not found on classpath", AGENT_PLATFORM_PROPERTIES);
            return null;
        }

        try (var inputStream = resource.getInputStream()) {
            java.util.Properties properties = new java.util.Properties();
            properties.load(inputStream);
            return properties.getProperty(EMBABEL_LOGGING_CONFIG_PROPERTY);
        } catch (IOException e) {
            log.error("Failed to read {} from {}", EMBABEL_LOGGING_CONFIG_PROPERTY, AGENT_PLATFORM_PROPERTIES, e);
            return null;
        }
    }

    /**
     * Checks if a resource exists on the classpath.
     *
     * @param name the resource name to check
     * @return {@code true} if the resource exists, {@code false} otherwise
     */
    private boolean resourceExists(String name) {
        return getClass().getClassLoader().getResource(name) != null;
    }

    /**
     * Returns the order for this post-processor.
     * <p>
     * Uses {@link Ordered#HIGHEST_PRECEDENCE} to run as early as possible,
     * ensuring {@code logging.config} is set before Spring Boot's logging
     * system initializes.
     *
     * @return the order value (highest precedence)
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
