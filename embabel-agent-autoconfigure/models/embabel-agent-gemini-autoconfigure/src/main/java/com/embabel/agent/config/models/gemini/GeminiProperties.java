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
package com.embabel.agent.config.models.gemini;

import com.embabel.agent.spi.common.RetryProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Google Gemini model settings.
 * These properties can be set in application.properties/yaml using the
 * prefix embabel.agent.platform.models.gemini.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.gemini")
public class GeminiProperties implements RetryProperties {

    /**
     * Maximum number of attempts.
     */
    private int maxAttempts = 10;

    /**
     * Initial backoff interval (in milliseconds).
     */
    private long backoffMillis = 5000L;

    /**
     * Backoff interval multiplier.
     */
    private double backoffMultiplier = 5.0;

    /**
     * Maximum backoff interval (in milliseconds).
     */
    private long backoffMaxInterval = 180000L;

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long getBackoffMillis() {
        return backoffMillis;
    }

    public void setBackoffMillis(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }

    @Override
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    @Override
    public long getBackoffMaxInterval() {
        return backoffMaxInterval;
    }

    public void setBackoffMaxInterval(long backoffMaxInterval) {
        this.backoffMaxInterval = backoffMaxInterval;
    }

    @Override
    public String toString() {
        return "GeminiProperties{" +
                "maxAttempts=" + maxAttempts +
                ", backoffMillis=" + backoffMillis +
                ", backoffMultiplier=" + backoffMultiplier +
                ", backoffMaxInterval=" + backoffMaxInterval +
                '}';
    }
}
