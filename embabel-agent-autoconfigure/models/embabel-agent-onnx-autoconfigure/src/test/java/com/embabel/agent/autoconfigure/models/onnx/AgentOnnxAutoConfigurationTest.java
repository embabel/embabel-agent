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
package com.embabel.agent.autoconfigure.models.onnx;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the ONNX entrypoint respects the ONNX embeddings enablement flag and does not register the initializer when the feature is disabled.
 */
class AgentOnnxAutoConfigurationTest {

   /**
    * Plain context runner for the ONNX entrypoint auto-configuration.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentOnnxAutoConfiguration.class));

   /**
    * Confirms that disabling ONNX embeddings prevents the ONNX initializer bean from being created, which is the observable effect of the provider-level condition.
    */
   @Test
   void doesNotRegisterInitializerWhenOnnxEmbeddingsDisabled() {
      // Arrange
      contextRunner
              .withPropertyValues("embabel.agent.platform.models.onnx.embeddings.enabled=false")
              // Act
              .run(context -> assertThat(context).doesNotHaveBean("onnxEmbeddingInitializer"));
   }
}
