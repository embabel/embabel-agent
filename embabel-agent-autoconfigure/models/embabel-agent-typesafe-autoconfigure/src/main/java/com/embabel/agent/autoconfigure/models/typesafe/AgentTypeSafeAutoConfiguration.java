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
package com.embabel.agent.autoconfigure.models.typesafe;

import com.embabel.agent.config.models.typesafe.TypeSafeModelsConfig;
import com.embabel.agent.typesafe.TypeSafeModelFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;

/**
 * Activates TypeSafe decision-service configuration when the integration is available. Runs after
 * the shared HTTP transport and before the community starter so both can reuse application transport
 * configuration without changing each other's beans.
 *
 * <p>Backs off when the application already provides its own named {@code typeSafeDecisionService}
 * bean or its own {@link TypeSafeModelFactory} bean, so a caller that builds the factory itself is
 * never asked for a credential this configuration doesn't need.
 */
@AutoConfiguration(
        beforeName = "org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration",
        afterName = "com.embabel.agent.autoconfigure.netty.NettyClientAutoConfiguration")
@ConditionalOnClass(TypeSafeModelFactory.class)
@ConditionalOnMissingBean(
        name = "typeSafeDecisionService",
        value = TypeSafeModelFactory.class)
@Import(TypeSafeModelsConfig.class)
public class AgentTypeSafeAutoConfiguration {}
