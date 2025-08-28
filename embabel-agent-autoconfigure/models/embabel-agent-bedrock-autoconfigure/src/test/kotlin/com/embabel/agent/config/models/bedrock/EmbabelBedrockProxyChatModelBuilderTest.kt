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
package com.embabel.agent.config.models.bedrock

import io.micrometer.observation.ObservationRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region.EU_WEST_3
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import java.time.Duration

@ExtendWith(OutputCaptureExtension::class)
class EmbabelBedrockProxyChatModelBuilderTest {

    @Test
    fun `AWS BedrockProxyChatModel builder does log warn message on init`(output: CapturedOutput) {
        BedrockProxyChatModel.builder()
        assertFalse { output.isEmpty() }
    }

    @Test
    fun `Custom BedrockProxyChatModel builder should not log warn message on init`(output: CapturedOutput) {
        EmbabelBedrockProxyChatModelBuilder()
        assertTrue { output.isEmpty() }
    }

    @Test
    fun `build should return a default chat model`() {
        val builder = EmbabelBedrockProxyChatModelBuilder()
        val chatModel = builder.build()
        assertNotNull(chatModel)
    }

    @Test
    fun `build should return a fully customized chat model`() {
        val region = EU_WEST_3
        val credentialsProvider = DefaultCredentialsProvider.create()
        val timeout = Duration.ofSeconds(1)
        val defaultOptions = ToolCallingChatOptions.builder().model("model").build()
        val toolCallingManager: ToolCallingManager = mockk()
        val observationRegistry = ObservationRegistry.NOOP
        val observationConvention = DefaultChatModelObservationConvention()

        val chatModel = EmbabelBedrockProxyChatModelBuilder()
            .toolCallingManager(toolCallingManager)
            .toolExecutionEligibilityPredicate(DefaultToolExecutionEligibilityPredicate())
            .customObservationConvention(observationConvention)
            .credentialsProvider(credentialsProvider)
            .region(region)
            .timeout(timeout)
            .defaultOptions(defaultOptions)
            .observationRegistry(observationRegistry)
            .bedrockRuntimeClient(
                BedrockRuntimeClient.builder()
                    .region(region)
                    .httpClientBuilder(null)
                    .credentialsProvider(credentialsProvider)
                    .overrideConfiguration { c -> c.apiCallTimeout(timeout) }
                    .build()
            )
            .bedrockRuntimeAsyncClient(
                BedrockRuntimeAsyncClient.builder()
                    .region(region)
                    .httpClientBuilder(
                        NettyNioAsyncHttpClient.builder()
                            .tcpKeepAlive(true)
                            .connectionAcquisitionTimeout(Duration.ofSeconds(30))
                            .maxConcurrency(200)
                    )
                    .credentialsProvider(credentialsProvider)
                    .overrideConfiguration { c -> c.apiCallTimeout(timeout) }
                    .build()
            )
            .build()

        assertNotNull(chatModel)
    }
}
