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
package com.embabel.agent.autoconfigure.netty;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyClientFactoryPropertiesTest {

    @Test
    void connectTimeout() {
        var defaults = new NettyClientFactoryProperties(null, null);
        assertEquals(Duration.ofSeconds(25), defaults.connectTimeout());

        var custom = new NettyClientFactoryProperties(Duration.ofSeconds(5), null);
        assertEquals(Duration.ofSeconds(5), custom.connectTimeout());
    }

    @Test
    void readTimeout() {
        var defaults = new NettyClientFactoryProperties(null, null);
        assertEquals(Duration.ofMinutes(1), defaults.readTimeout());

        var custom = new NettyClientFactoryProperties(null, Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(10), custom.readTimeout());
    }
}