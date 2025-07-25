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
package com.embabel.agent.autoconfigure.platform;

import com.embabel.agent.config.annotation.AgentPlatform;
import com.embabel.agent.event.AgenticEventListener;
import com.embabel.agent.rag.RagService;
import com.embabel.agent.spi.Ranker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Java INTEGRATION test for AgentPlatformAutoConfiguration.
 * Test employs OPEN API KEY.
 *
 */
@SpringBootTest(classes= AgentPlatformAutoConfigurationIT.class)
@ComponentScan(basePackages = "com.embabel.agent.autoconfigure")
@ImportAutoConfiguration(classes = {AgentPlatformAutoConfiguration.class})
@AgentPlatform("my-domain")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentPlatformAutoConfigurationIT {


    @Autowired
    private AgenticEventListener eventListener;

    @Autowired
    private Ranker ranker;

    @Autowired
    private RagService defaultSpringVectorStore;

    @BeforeEach
    void setUp() {
    }


    @AfterEach
    void tearDown() {
    }


    @Test
    public void testAutoConfiguredBeanPresence() {
        Assertions.assertNotNull(eventListener, "Event Listener should be Auto-Configured");
        Assertions.assertNotNull(ranker, "Ranker should be Auto-Configured");
        Assertions.assertNotNull(defaultSpringVectorStore, "RagService should be Auto-Configured");

    }


}