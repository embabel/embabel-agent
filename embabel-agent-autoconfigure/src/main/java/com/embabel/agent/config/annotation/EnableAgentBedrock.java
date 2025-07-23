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

import com.embabel.agent.config.models.BedrockModels;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables Embabel Agent integration with AWS Bedrock for enterprise-grade AI model access.
 *
 * <p>This annotation configures your Spring Boot application to use AWS Bedrock as the
 * primary AI model provider, enabling access to foundation models like Claude, Titan,
 * and other AWS-hosted models through a unified interface.
 *
 * <h3>What This Provides:</h3>
 * <ul>
 *   <li>Activates both "shell" and "bedrock" Spring profiles</li>
 *   <li>Configures AWS Bedrock client with proper authentication</li>
 *   <li>Enables interactive shell interface for agent testing</li>
 *   <li>Sets up model routing to use Bedrock-hosted models</li>
 *   <li>Provides enterprise security and compliance features</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * @SpringBootApplication
 * @EnableAgentBedrock
 * public class BedrockAgentApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(BedrockAgentApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <h3>Required AWS Configuration:</h3>
 * <p>Ensure AWS credentials are configured via one of these methods:
 * <ul>
 *   <li>AWS credentials file: {@code ~/.aws/credentials}</li>
 *   <li>Environment variables: {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}</li>
 *   <li>IAM role (when running on EC2/ECS/Lambda)</li>
 *   <li>AWS SSO configuration</li>
 * </ul>
 *
 * <h3>Configuration Properties:</h3>
 * <pre>{@code
 * # application.yml
 * aws:
 *   bedrock:
 *     region: us-east-1              # AWS region (default: us-east-1)
 *     model-id: anthropic.claude-v2  # Default model to use
 *     max-tokens: 4096               # Maximum tokens per request
 *     temperature: 0.7               # Model temperature
 *
 * embabel:
 *   bedrock:
 *     retry-attempts: 3              # Retry failed requests
 *     timeout: 30000                 # Request timeout in ms
 *     cache-enabled: true            # Enable response caching
 * }</pre>
 *
 * <h3>Available Bedrock Models:</h3>
 * <ul>
 *   <li>{@code anthropic.claude-v2} - Claude 2 model</li>
 *   <li>{@code anthropic.claude-instant-v1} - Claude Instant</li>
 *   <li>{@code amazon.titan-text-express-v1} - Amazon Titan</li>
 *   <li>{@code ai21.j2-ultra-v1} - Jurassic-2 Ultra</li>
 *   <li>{@code meta.llama2-13b-chat-v1} - Llama 2 Chat</li>
 * </ul>
 *
 * @see EnableAgentShell
 * @see EnableAgents
 * @see AgentPlatform
 * @since 1.0
 * @author Embabel Team
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@AgentPlatform({StartupMode.SHELL, BedrockModels.BEDROCK_PROFILE})
public @interface EnableAgentBedrock {
}
