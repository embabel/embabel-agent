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
package com.embabel.agent.autoconfigure.chatstore;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Embabel Chat Store.
 *
 * <p>Properties are bound from configuration with the prefix {@code embabel.chat.store}:
 * <pre>
 * embabel.chat.store.enabled=true
 * </pre>
 */
@ConfigurationProperties(prefix = "embabel.chat.store")
public class ChatStoreProperties {

    /**
     * Whether chat store autoconfiguration is enabled.
     * When false, no ConversationFactory beans are created by autoconfiguration.
     * Defaults to true.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}