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
package com.embabel.agent.autoconfigure.observability;

import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.agent.observability.observation.TrackedAspect;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for {@link TrackedAspect}.
 * Isolated in a separate class so that the TrackedAspect and AspectJ classes
 * are only loaded when AspectJ is on the classpath.
 *
 * @since 0.3.4
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
@ConditionalOnProperty(prefix = "embabel.observability", name = {"enabled", "trace-tracked-operations"}, havingValue = "true", matchIfMissing = true)
public class TrackedAspectAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TrackedAspectAutoConfiguration.class);

    /**
     * Creates the aspect for {@code @Tracked} annotation support.
     *
     * @param observationRegistry the observation registry
     * @param properties          the observability properties
     * @return the tracked aspect
     */
    @Bean
    @ConditionalOnMissingBean
    public TrackedAspect trackedAspect(ObservationRegistry observationRegistry, ObservabilityProperties properties) {
        log.info("Configuring @Tracked annotation aspect for custom operation tracking");
        return new TrackedAspect(observationRegistry, properties.getMaxAttributeLength());
    }
}
