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

import com.embabel.agent.config.annotation.AgentPlatform;
import com.embabel.agent.config.annotation.EnableAgents;
import com.embabel.common.util.WinUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Environment post-processor that activates Spring profiles based on Embabel Agent annotations.
 *
 * <p>This processor runs very early in the Spring Boot startup sequence and examines
 * the application's main class for agent-related annotations. It then activates
 * corresponding Spring profiles to enable platform-specific configurations.
 *
 * <h3>Processing Order:</h3>
 * <ol>
 *   <li><b>Platform Profiles</b> - From {@code @AgentPlatform} or meta-annotations like {@code @EnableAgentShell}</li>
 *   <li><b>Logging Theme</b> - From {@code @EnableAgents(loggingTheme="...")}</li>
 *   <li><b>Local Models</b> - From {@code @EnableAgents(localModels={...})}</li>
 *   <li><b>MCP Servers</b> - From {@code @EnableAgents(mcpServers={...})}</li>
 * </ol>
 *
 * <h3>Profile Activation:</h3>
 * <p>Profiles are activated through two mechanisms:
 * <ul>
 *   <li>System property: {@code spring.profiles.active} - for compatibility</li>
 *   <li>Environment API: {@code environment.addActiveProfile()} - for direct activation</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * @SpringBootApplication
 * @EnableAgentShell  // Activates "shell" profile
 * @EnableAgents(
 *     loggingTheme = LoggingThemes.START_WARS
 *     localModels = {LocalModels.OLAMA},
 *     mcpServer = {McpServers.DOCKER_DESKTOP}
 * )
 * public class MyApp {
 *     // Result: Application comes up with Start Wars Theme, Local Models for Ollama
 *     // and will try to connect to Docker Desktop MCP server.
 * }
 * }</pre>
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
 * @see AgentPlatform
 * @see org.springframework.boot.env.EnvironmentPostProcessor
 * @since 1.0
 */
public class EnvironmentPostProcessor implements org.springframework.boot.env.EnvironmentPostProcessor, Ordered {

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
        // Use LinkedHashSet to maintain order and avoid duplicates
        var allProfiles = new LinkedHashSet<String>();

        // 1. Get platform profiles from @AgentPlatform
        var agentProfiles = findPlatformProfiles(application);
        if (agentProfiles.length > 0) {
            allProfiles.addAll(Arrays.asList(agentProfiles));
            logger.debug("Found platform profiles: {}", Arrays.toString(agentProfiles));
        }

        // 2. Get profile from loggingTheme
        var themeProfile = findLoggingTheme(application);
        if (themeProfile != null && !themeProfile.isEmpty()) {
            allProfiles.add(themeProfile);
            logger.info("Found loggingTheme '{}' - adding profile: {}", themeProfile, themeProfile);
        }

        // 3. Get profiles from localModels
        var localModelsProfiles = findLocalModels(application);
        if (ArrayUtils.isNotEmpty(localModelsProfiles)) {
            allProfiles.addAll(Arrays.asList(localModelsProfiles));
            logger.info("Found localModels - adding profiles: {}", Arrays.toString(localModelsProfiles));
        }

        // 4. Get profiles from mcpServers
        var mcpServerProfiles = findMcpServers(application);
        if (ArrayUtils.isNotEmpty(mcpServerProfiles)) {
            allProfiles.addAll(Arrays.asList(mcpServerProfiles));
            logger.info("Found mcpServers - adding profiles: {}", Arrays.toString(mcpServerProfiles));
        }

        // Apply all collected profiles
        if (!allProfiles.isEmpty()) {
            activateProfiles(environment, allProfiles);
        }
    }

    /**
     * Finds platform profiles from {@code @AgentPlatform} annotations.
     *
     * <p>This method handles both direct usage and meta-annotations
     * (e.g., {@code @EnableAgentShell} which has {@code @AgentPlatform("shell")}).
     *
     * @param application the Spring application
     * @return array of platform profile names, empty if none found
     */
    private String[] findPlatformProfiles(SpringApplication application) {
        var allPlatformProfiles = new LinkedHashSet<String>();

        for (Object source : application.getAllSources()) {
            if (source instanceof Class<?> clazz) {
                // Use MergedAnnotations to find ALL occurrences of @AgentPlatform
                MergedAnnotations annotations = MergedAnnotations.from(clazz);

                // Stream through all @AgentPlatform annotations (direct and meta)
                annotations.stream(AgentPlatform.class)
                        .forEach(mergedAnnotation -> {
                            String[] values = mergedAnnotation.getStringArray("value");
                            allPlatformProfiles.addAll(Arrays.asList(values));
                        });
            }
        }

        logger.debug("Collected all platform profiles: {}", allPlatformProfiles);
        return allPlatformProfiles.toArray(new String[0]);
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
     * Finds local model profiles from {@code @EnableAgents} annotation.
     *
     * @param application the Spring application
     * @return array of local model names, empty if none specified
     */
    private String[] findLocalModels(SpringApplication application) {
        EnableAgents enableAgents = findEnableAgentsAnnotation(application);
        // Return array or empty array to avoid null
        return enableAgents != null ? enableAgents.localModels() : new String[0];
    }

    /**
     * Finds MCP server profile from {@code @EnableAgents} annotation.
     *
     * @param application the Spring application
     * @return array of MCP server names, empty if none specified
     */
    private String[] findMcpServers(SpringApplication application) {
        EnableAgents enableAgents = findEnableAgentsAnnotation(application);
        // Return array or empty array to avoid null
        return enableAgents != null ? enableAgents.mcpServers() : new String[0];
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
     * Activates the collected profiles in both system properties and the environment.
     *
     * @param environment the Spring environment
     * @param profiles    the profiles to activate
     */
    private void activateProfiles(ConfigurableEnvironment environment, Set<String> profiles) {
        // Get existing profiles from system property
        String existingProfiles = System.getProperty(SPRING_PROFILES_ACTIVE);
        
        if (existingProfiles != null && !existingProfiles.isEmpty()) {
            // Merge with existing profiles, maintaining uniqueness
            var mergedProfiles = new LinkedHashSet<String>();
            // Add existing profiles first to maintain order
            mergedProfiles.addAll(Arrays.asList(existingProfiles.split(",")));
            // Add new profiles
            mergedProfiles.addAll(profiles);
            // Update environment
            mergedProfiles.forEach(environment::addActiveProfile);
        } else {
            profiles.forEach(environment::addActiveProfile);
        }


        // Log the final state for debugging
        logger.info("Activated Spring profiles: {}", (Object[]) environment.getActiveProfiles());
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