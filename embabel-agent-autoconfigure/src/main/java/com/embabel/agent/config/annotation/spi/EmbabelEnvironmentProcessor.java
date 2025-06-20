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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;


/**
 * Sets Spring Active Profile from [EnableAgents] annotation.
 */
public class EmbabelEnvironmentProcessor implements EnvironmentPostProcessor, Ordered {

    private Logger logger = LoggerFactory.getLogger(EmbabelEnvironmentProcessor.class);

    private final static String SPRING_PROFILES_ACTIVE = "spring.profiles.active";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] profiles = findProfilesFromAnnotation(application);
        if (profiles.length > 0) {
            // Set system property (mimics export SPRING_PROFILES_ACTIVE)
            String existingProfiles = System.getProperty(SPRING_PROFILES_ACTIVE);
            String newProfiles = String.join(",", profiles);

            if (existingProfiles != null && !existingProfiles.isEmpty()) {
                System.setProperty(SPRING_PROFILES_ACTIVE, existingProfiles + "," + newProfiles);
            } else {
                System.setProperty("spring.profiles.active", newProfiles);
            }
            logger.info("Set Active Profile as {}", System.getProperty(SPRING_PROFILES_ACTIVE));
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
                 * Get "value" attribute of child annotation  by hierarchy, such as EnableAgentShell, etc.
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


    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

