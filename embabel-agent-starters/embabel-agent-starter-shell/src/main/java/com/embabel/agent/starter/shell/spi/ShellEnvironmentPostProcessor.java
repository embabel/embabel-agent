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
package com.embabel.agent.starter.shell.spi;

import com.embabel.agent.starter.shell.AgentShellStarterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Environment post-processor that configures shell mode early in the Spring Boot lifecycle.*
 * <p>The processor runs early in the Spring Boot lifecycle to ensure shell configuration is
 * applied before the application context is initialized, preventing conflicts with web server
 * startup when operating in shell mode.
 */
public class ShellEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(ShellEnvironmentPostProcessor.class);

    // Property source configuration
    private static final String SHELL_PROPERTIES_SOURCE_NAME = "shellModeProperties";
    private static final String SHELL_CONFIG_PREFIX = "embabel.agent.shell";

    // Spring Boot property keys
    private static final String WEB_APPLICATION_TYPE_PROPERTY = "spring.main.web-application-type";
    private static final String SHELL_EXIT_ENABLED_PROPERTY = "spring.shell.command.exit.enabled";
    private static final String SHELL_QUIT_ENABLED_PROPERTY = "spring.shell.command.quit.enabled";
    private static final String SHELL_INTERACTIVE_ENABLED_PROPERTY = "spring.shell.interactive.enabled";
    private static final String SHELL_HISTORY_ENABLED_PROPERTY = "spring.shell.history.enabled";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // Run early, but after core Spring Boot processors
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
       logger.debug("Shell mode detected - applying shell environment configuration");

        ShellConfiguration shellConfig = resolveShellConfiguration(environment);
        applyShellConfiguration(environment, shellConfig);

        logger.debug("Shell environment configuration applied successfully");
    }

    /**
     * Resolves the shell configuration from the environment, falling back to defaults
     * if binding fails or properties are not found.
     *
     * @param environment the configurable environment
     * @return the resolved shell configuration
     */
    private ShellConfiguration resolveShellConfiguration(ConfigurableEnvironment environment) {
        try {
            AgentShellStarterProperties properties = Binder.get(environment)
                    .bind(SHELL_CONFIG_PREFIX, AgentShellStarterProperties.class)
                    .orElseGet(AgentShellStarterProperties::new);

            return new ShellConfiguration(properties);
        } catch (BindException e) {
            logger.warn("Failed to bind shell properties from '{}', using defaults: {}",
                    SHELL_CONFIG_PREFIX, e.getMessage());
            return new ShellConfiguration(new AgentShellStarterProperties());
        } catch (Exception e) {
            logger.error("Unexpected error while resolving shell configuration, using defaults", e);
            return new ShellConfiguration(new AgentShellStarterProperties());
        }
    }

    /**
     * Applies the shell configuration to the environment by adding a high-priority property source.
     *
     * @param environment the configurable environment
     * @param shellConfig the shell configuration to apply
     */
    private void applyShellConfiguration(ConfigurableEnvironment environment, ShellConfiguration shellConfig) {
        Map<String, Object> shellProperties = buildShellProperties(shellConfig);

        environment.getPropertySources().addFirst(
                new MapPropertySource(SHELL_PROPERTIES_SOURCE_NAME, shellProperties)
        );

        logger.debug("Added shell properties with {} entries", shellProperties.size());
    }

    /**
     * Builds the shell properties map from the configuration.
     *
     * <p>Sets the web application type to NONE to prevent web server startup when in shell mode,
     * ensuring the application operates as a command-line tool rather than a web service.
     *
     * @param shellConfig the shell configuration
     * @return map of property names to values
     */
    private Map<String, Object> buildShellProperties(ShellConfiguration shellConfig) {
        Map<String, Object> properties = new HashMap<>();

        // Configure application type for shell mode - prevents web server startup
        properties.put(WEB_APPLICATION_TYPE_PROPERTY, shellConfig.getWebApplicationType());

        // Configure Spring Shell behavior
        properties.put(SHELL_EXIT_ENABLED_PROPERTY, shellConfig.isExitCommandEnabled());
        properties.put(SHELL_QUIT_ENABLED_PROPERTY, shellConfig.isQuitCommandEnabled());
        properties.put(SHELL_INTERACTIVE_ENABLED_PROPERTY, shellConfig.isInteractiveMode());
        properties.put(SHELL_HISTORY_ENABLED_PROPERTY, shellConfig.isHistoryEnabled());

        return properties;
    }

    /**
     * Domain object representing shell configuration extracted from AgentShellStarterProperties.
     * This provides a clean abstraction over the raw properties and encapsulates shell-specific logic.
     */
    private static class ShellConfiguration {
        private final AgentShellStarterProperties properties;

        public ShellConfiguration(AgentShellStarterProperties properties) {
            this.properties = properties;
        }

        public String getWebApplicationType() {
            return properties.getWebApplicationType();
        }

        public boolean isExitCommandEnabled() {
            return properties.getCommand().isExitEnabled();
        }

        public boolean isQuitCommandEnabled() {
            return properties.getCommand().isQuitEnabled();
        }

        public boolean isInteractiveMode() {
            return properties.getInteractive().isEnabled();
        }

        public boolean isHistoryEnabled() {
            return properties.getInteractive().isHistoryEnabled();
        }
    }
}