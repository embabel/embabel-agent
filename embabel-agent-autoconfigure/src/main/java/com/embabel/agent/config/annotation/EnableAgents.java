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
package com.embabel.agent.config.annotation;

import com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Annotation for  Enabling Agent AutoConfiguration and setting AgentApplication Active spring profile.
 */
@Retention(RetentionPolicy.RUNTIME) // Keep the annotation at runtime for reflection
@Target(ElementType.TYPE)           // Apply the annotation to classes/types
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@ComponentScan(
        basePackages = {
                "com.embabel.agent.autoconfigure"
        }
)
@ImportAutoConfiguration(classes={AgentPlatformAutoConfiguration.class})
public @interface EnableAgents {
    String[] value() default {"default"};
}
