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
package com.embabel.agent.autoconfigure.platform;

import com.embabel.agent.spi.common.RetryProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retry settings for LLM-backed decision services, bound from
 * {@code embabel.agent.platform.decisions.llm}. The defaults match the other platform services that
 * call a model. Startup fails on any value spring-retry would reject, and on fewer than one attempt,
 * which would fail every call without asking the model.
 */
@ConfigurationProperties(prefix = LlmDecisionRetryProperties.PREFIX)
final class LlmDecisionRetryProperties implements RetryProperties {

    static final String PREFIX = "embabel.agent.platform.decisions.llm";

    private final int maxAttempts;

    private final long backoffMillis;

    private final double backoffMultiplier;

    private final long backoffMaxInterval;

    /**
     * @param maxAttempts most calls made for one decision, counting the first; at least 1
     * @param backoffMillis wait before the first retry, in milliseconds; at least 1
     * @param backoffMultiplier how much each wait grows over the last; greater than 1
     * @param backoffMaxInterval longest wait between retries, in milliseconds; greater than backoffMillis
     * @throws IllegalArgumentException if a setting is out of range, with a message naming the property
     */
    LlmDecisionRetryProperties(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("100") long backoffMillis,
            @DefaultValue("5.0") double backoffMultiplier,
            @DefaultValue("180000") long backoffMaxInterval) {
        require(maxAttempts >= 1, "max-attempts must be at least 1");
        require(backoffMillis >= 1, "backoff-millis must be at least 1");
        require(backoffMultiplier > 1.0, "backoff-multiplier must be greater than 1");
        require(backoffMaxInterval > backoffMillis, "backoff-max-interval must be greater than backoff-millis");
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
        this.backoffMultiplier = backoffMultiplier;
        this.backoffMaxInterval = backoffMaxInterval;
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public long getBackoffMillis() {
        return backoffMillis;
    }

    @Override
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    @Override
    public long getBackoffMaxInterval() {
        return backoffMaxInterval;
    }

    @Override
    public String getPropertyPrefix() {
        return PREFIX;
    }
}
