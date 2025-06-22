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

import com.embabel.agent.config.annotation.EnableAgentMcp;
import com.embabel.agent.config.annotation.EnableAgents;
import com.embabel.agent.config.annotation.EnableAgentShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sets Spring Active Profile from [EnableAgents] annotation and processes loggingTheme.
 */
public class EmbabelEnvironmentProcessor implements EnvironmentPostProcessor, Ordered {

    private final Logger logger = LoggerFactory.getLogger(EmbabelEnvironmentProcessor.class);

    private final static String SPRING_PROFILES_ACTIVE = "spring.profiles.active";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> allProfiles = new ArrayList<>();

        // Get profiles from @EnableAgents
        String[] agentProfiles = findProfilesFromAnnotation(application);
        if (agentProfiles.length > 0) {
            allProfiles.addAll(Arrays.asList(agentProfiles));
        }

        // Get profile from loggingTheme
        String themeProfile = findLoggingThemeProfile(application);
        if (themeProfile != null && !themeProfile.isEmpty()) {
            allProfiles.add(themeProfile);
            logger.info("Found loggingTheme: {} - adding profile: {}",
                    getLoggingTheme(application), themeProfile);
        }

        // Apply all profiles
        if (!allProfiles.isEmpty()) {
            // Set system property (mimics export SPRING_PROFILES_ACTIVE)
            String existingProfiles = System.getProperty(SPRING_PROFILES_ACTIVE);
            String newProfiles = String.join(",", allProfiles);

            if (existingProfiles != null && !existingProfiles.isEmpty()) {
                System.setProperty(SPRING_PROFILES_ACTIVE, existingProfiles + "," + newProfiles);
            } else {
                System.setProperty(SPRING_PROFILES_ACTIVE, newProfiles);
            }

            // Also add profiles directly to the environment
            for (String profile : allProfiles) {
                environment.addActiveProfile(profile);
            }

            logger.info("Set Active Profiles: {}", System.getProperty(SPRING_PROFILES_ACTIVE));
        }
    }

    private String[] findProfilesFromAnnotation(SpringApplication application) {
        for (Object source : application.getAllSources()) {
            if (source instanceof Class) {
                /*
                 * Generic EmbabelAgents annotation
                 */
                EnableAgents enableProfile = AnnotationUtils.findAnnotation((Class<?>) source, EnableAgents.class);
                /*
                 * Get "value" attribute of child annotation by hierarchy, such as EnableAgentShell, etc.
                 */
                AnnotationAttributes mergedAnnotationAttributes = AnnotatedElementUtils.getMergedAnnotationAttributes(
                        (Class) source, EnableAgents.class);

                if (mergedAnnotationAttributes != null && !mergedAnnotationAttributes.isEmpty()) {
                    return mergedAnnotationAttributes.getStringArray("value");
                } else if (enableProfile != null) {
                    return enableProfile.value();
                }
            }
        }
        return new String[0];
    }

    private String getLoggingTheme(SpringApplication application) {
        for (Object source : application.getAllSources()) {
            if (source instanceof Class) {
                // Check for @EnableAgentShell annotation
                EnableAgentShell enableAgentShell = AnnotationUtils.findAnnotation(
                        (Class<?>) source, EnableAgentShell.class);

                EnableAgentMcp enableAgentMcp = AnnotationUtils.findAnnotation(
                        (Class<?>) source, EnableAgentMcp.class);

                if (enableAgentShell != null) {
                    return enableAgentShell.loggingTheme().getTheme();
                }

                if (enableAgentMcp != null) {
                    return enableAgentMcp.loggingTheme().getTheme();
                }
            }
        }
        return "";
    }

    private String findLoggingThemeProfile(SpringApplication application) {
        String loggingTheme = getLoggingTheme(application);

        // Map theme to profile
        switch (loggingTheme) {
            case "starwars":
                return "starwars";
            case "severance":
                return "severance";
            default:
                return null;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}