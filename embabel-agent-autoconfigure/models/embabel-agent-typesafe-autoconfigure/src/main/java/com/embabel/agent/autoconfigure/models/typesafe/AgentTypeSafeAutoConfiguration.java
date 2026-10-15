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
import com.embabel.agent.config.models.typesafe.TypeSafeProperties;
import com.embabel.agent.typesafe.TypeSafeModelFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activates TypeSafe decision-service configuration when the integration is available. Runs after
 * the shared HTTP transport and before the community starter so both can reuse application transport
 * configuration without changing each other's beans.
 *
 * <p>An application that supplies its own {@link TypeSafeModelFactory} keeps it, and this
 * configuration then does not run: it creates no factory, no default service and no named
 * services, and needs no credential. An application bean named {@code typeSafeDecisionService}
 * replaces the default service. The configuration still runs when services are configured under
 * {@code services}, so those services register. With the application bean and no configured
 * services, the configuration does not run and needs no credential.
 */
@AutoConfiguration(
        beforeName = "org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration",
        afterName = "com.embabel.agent.autoconfigure.netty.NettyClientAutoConfiguration")
@ConditionalOnClass(TypeSafeModelFactory.class)
@ConditionalOnMissingBean(TypeSafeModelFactory.class)
@Conditional(AgentTypeSafeAutoConfiguration.DefaultServiceMissingOrServicesConfigured.class)
@Import(TypeSafeModelsConfig.class)
public class AgentTypeSafeAutoConfiguration {

    private AgentTypeSafeAutoConfiguration() {}

    /** Matches when the default service is missing or at least one named service is configured. */
    static final class DefaultServiceMissingOrServicesConfigured extends AnyNestedCondition {

        DefaultServiceMissingOrServicesConfigured() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnMissingBean(name = TypeSafeModelsConfig.DEFAULT_SERVICE)
        static final class DefaultServiceMissing {}

        @Conditional(ServicesConfigured.class)
        static final class NamedServicesConfigured {}
    }

    /** Matches when the environment holds at least one entry under {@code services}. */
    static final class ServicesConfigured extends SpringBootCondition {

        private static final String SERVICES = TypeSafeProperties.PREFIX + ".services";

        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean configured =
                    Binder.get(context.getEnvironment())
                            .bind(SERVICES, Bindable.mapOf(String.class, Object.class))
                            .map(services -> !services.isEmpty())
                            .orElse(false);
            return configured
                    ? ConditionOutcome.match(SERVICES + " has entries")
                    : ConditionOutcome.noMatch(SERVICES + " has no entries");
        }
    }
}
