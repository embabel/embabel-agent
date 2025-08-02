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
package com.embabel.agent.config.annotation.bedrock;

import com.embabel.agent.config.annotation.AgentPlatform;
import com.embabel.agent.config.models.bedrock.BedrockModels;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables Embabel Agent integration with AWS Bedrock for enterprise-grade AI model access.
 *
 * <p>This annotation configures your Spring Boot application to use AWS Bedrock as
 * AI model provider, enabling access to foundation models like Claude, Titan,
 * and other AWS-hosted models through a unified interface.
 *
 * <h3>What This Provides:</h3>
 * <ul>
 *   <li>Activates "bedrock" Spring profile</li>
 *   <li>Configures AWS Bedrock client with proper authentication (using AwsCredentialsProvider through org.springframework.ai:spring-ai-bedrock library)</li>
 *   <li>Sets up model routing to use Bedrock-hosted models</li>
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
 *   <li>Environment variables: {@code AWS_REGION}, {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}</li>
 *   <li>IAM role (when running on EC2/ECS/Lambda)</li>
 *   <li>AWS SSO configuration</li>
 * </ul>
 *
 * <h3>Configuration Properties:</h3>
 * <pre>{@code
 * # application.yml
 * embabel:
 *   models:
 *     default-llm: eu.anthropic.claude-sonnet-4-20250514-v1:0
 *     default-embedding-model: cohere.embed-multilingual-v3
 * }</pre>
 *
 * See BedrockModels for other available Bedrock Models.
 * <p>If a more convenient model is needed, you may also override the provided application-bedrock.yml model list.
 *
 * @see com.embabel.agent.config.annotation.EnableAgents
 * @see AgentPlatform
 * @see BedrockModels
 * @since 1.0
 * @author Embabel Team
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@AgentPlatform(BedrockModels.BEDROCK_PROFILE)
public @interface EnableAgentBedrock {
}
