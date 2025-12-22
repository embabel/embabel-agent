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
package com.embabel.agent.config.annotation.spi;

import com.embabel.agent.config.annotation.EnableAgents;
import com.embabel.common.util.WinUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Environment post-processor that activates Spring profiles and sets properties
 * used to bootstrap framework components based on Embabel Agent annotations.
 *
 * <p>This processor runs very early in the Spring Boot startup sequence and examines
 * the application's main class for agent-related annotations. It then activates
 * corresponding Spring profiles to enable platform-specific configurations.
 *
 * <h3>Processing Order:</h3>
 * <ol>
 *   <li><b>Logging Theme</b> - From {@code @EnableAgents(loggingTheme="...")}</li>
 * </ol>
 *
 * <h3>Profile Activation:</h3>
 * <p>Profiles are activated through two mechanisms:
 * <ul>
 *   <li>System property: {@code spring.profiles.active} - for compatibility</li>
 *   <li>Environment API: {@code environment.addActiveProfile()} - for direct activation</li>
 * </ul>
 *
 * </pre>
 *
 * <h3>Implementation Notes:</h3>
 * <ul>
 *   <li>Runs with {@link Ordered#HIGHEST_PRECEDENCE} to ensure early execution</li>
 *   <li>Preserves existing profiles if already set</li>
 *   <li>Handles multiple application sources (though typically there's only one)</li>
 *   <li>Uses both annotation utils for proper meta-annotation support</li>
 * </ul>
 *
 * @author Embabel Team
 * @see EnableAgents
 * @see org.springframework.boot.env.EnvironmentPostProcessor
 * @since 1.0
 */
public class EnvironmentPostProcessor implements org.springframework.boot.env.EnvironmentPostProcessor, Ordered {

    public static final String LOGGING_THEME_PROPERTY = "embabel.agent.logging.personality";

    private final Logger logger = LoggerFactory.getLogger(EnvironmentPostProcessor.class);

    private static final String SPRING_PROFILES_ACTIVE = "spring.profiles.active";

    static {
        if (WinUtils.IS_OS_WINDOWS()) {
            // Set console to UTF-8 on Windows and optimize font for Unicode display
            // This is necessary to display non-ASCII characters correctly
            WinUtils.CHCP_TO_UTF8();
            WinUtils.SETUP_OPTIMAL_CONSOLE();
        }
    }

    /**
     * Post-processes the environment to activate profiles based on agent annotations.
     *
     * @param environment the environment to post-process
     * @param application the Spring application
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

        // 1. Get loggingTheme and set corresponding properties
        var loggingTheme = findLoggingTheme(application);
        if (loggingTheme != null && !loggingTheme.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("loggingThemeSource",
                            java.util.Collections.singletonMap(LOGGING_THEME_PROPERTY, loggingTheme)));
            logger.info("Found loggingTheme '{}' - adding property: {}", LOGGING_THEME_PROPERTY, loggingTheme);
        }

    }


    /**
     * Finds the logging theme from {@code @EnableAgents} annotation.
     *
     * @param application the Spring application
     * @return logging theme name, or empty string if not specified
     */
    private String findLoggingTheme(SpringApplication application) {
        EnableAgents enableAgents = findEnableAgentsAnnotation(application);
        // Return theme or empty string to avoid null handling
        return enableAgents != null ? enableAgents.loggingTheme() : "";
    }

    /**
     * Finds the {@code @EnableAgents} annotation on the application class.
     *
     * @param application the Spring application
     * @return the annotation instance, or null if not found
     */
    private EnableAgents findEnableAgentsAnnotation(SpringApplication application) {
        // Search through all sources for @EnableAgents
        for (Object source : application.getAllSources()) {
            if (source instanceof Class<?> clazz) {
                // Use AnnotationUtils to handle inheritance and interfaces
                EnableAgents enableAgents = AnnotationUtils.findAnnotation(clazz, EnableAgents.class);
                if (enableAgents != null) {
                    return enableAgents;
                }
            }
        }
        return null;
    }

    /**
     * Returns the order of this post-processor.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE} to ensure early execution
     */
    @Override
    public int getOrder() {
        // Run as early as possible to ensure profiles are set before beans are created
        return Ordered.HIGHEST_PRECEDENCE;
    }
}