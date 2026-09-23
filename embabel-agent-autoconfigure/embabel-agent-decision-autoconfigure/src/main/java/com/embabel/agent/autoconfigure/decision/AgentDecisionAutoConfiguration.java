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
package com.embabel.agent.autoconfigure.decision;

import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionRecordPolicy;
import com.embabel.agent.decision.NoDecisionModel;
import com.embabel.agent.decision.llm.PromptedDecisionModel;
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import org.jetbrains.annotations.ApiStatus;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/** Opt-in configuration for the experimental typed decision model. */
@ApiStatus.Experimental
@AutoConfiguration
@ConditionalOnClass(DecisionModel.class)
@ConditionalOnProperty(prefix = AgentDecisionAutoConfiguration.PREFIX, name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(DecisionModel.class)
public class AgentDecisionAutoConfiguration {
    static final String PREFIX = "embabel.agent.decision";

    @Bean
    DecisionProperties decisionProperties(Environment environment) {
        return readProperties(environment);
    }

    @Bean(name = "decisionModel")
    DecisionModel decisionModel(DecisionProperties properties, Environment environment, BeanFactory beanFactory) {
        DecisionRecordPolicy policy = recordPolicy(properties);
        DecisionModel model = switch (properties.getProvider()) {
            case "none" -> NoDecisionModel.create();
            case "typesafe" -> typesafe(properties, environment, beanFactory);
            case "prompted" -> prompted(properties, beanFactory);
            default -> throw invalid("provider");
        };
        return model.withDefaults(properties.getDefaultTimeout(), policy);
    }

    private DecisionModel typesafe(DecisionProperties properties, Environment environment, BeanFactory beanFactory) {
        var selected = properties.typesafe();
        if (!TypeSafeDecisionModel.supportsBaseUri(selected.getBaseUrl())) throw invalid("typesafe.base-url");
        EmbabelObjectMapperHolder mapper = mapper(properties, beanFactory);
        Supplier<String> apiKey = () -> {
            String configured = environment.getProperty(PREFIX + ".typesafe.api-key");
            if (configured != null && !configured.isBlank()) return configured;
            String fallback = environment.getProperty("TYPESAFE_API_KEY");
            return fallback == null ? "" : fallback;
        };
        if (mapper == null) {
            return TypeSafeDecisionModel.create(apiKey, selected.getModel(), selected.getBaseUrl(), selected.getConnectTimeout());
        }
        return TypeSafeDecisionModel.create(apiKey, selected.getModel(), selected.getBaseUrl(), selected.getConnectTimeout(), mapper);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DecisionModel prompted(DecisionProperties properties, BeanFactory beanFactory) {
        String serviceName = properties.prompted().getLlmBeanName();
        LlmService service = exactBean(beanFactory, serviceName, LlmService.class, "prompted.llm-bean-name");
        String optionsName = properties.prompted().getOptionsBeanName();
        LlmOptions options = optionsName == null
                ? LlmOptions.withDefaults()
                : exactBean(beanFactory, optionsName, LlmOptions.class, "prompted.options-bean-name");
        EmbabelObjectMapperHolder mapper = mapper(properties, beanFactory);
        return mapper == null
                ? PromptedDecisionModel.create(service, options)
                : PromptedDecisionModel.create(service, options, mapper);
    }

    private EmbabelObjectMapperHolder mapper(DecisionProperties properties, BeanFactory beanFactory) {
        String name = properties.getMapperBeanName();
        return name == null ? null : exactBean(beanFactory, name, EmbabelObjectMapperHolder.class, "mapper-bean-name");
    }

    private static <T> T exactBean(BeanFactory factory, String name, Class<T> type, String key) {
        try {
            return factory.getBean(name, type);
        } catch (RuntimeException ignored) {
            throw invalid(key);
        }
    }

    private static DecisionRecordPolicy recordPolicy(DecisionProperties properties) {
        return switch (properties.getRecordMode()) {
            case "none" -> DecisionRecordPolicy.none();
            case "metadata" -> DecisionRecordPolicy.metadata();
            case "full" -> {
                try {
                    yield DecisionRecordPolicy.full(properties.getFullRecordMaxBytes(), properties.getRecordAllowlist());
                } catch (RuntimeException ignored) {
                    throw invalid("record-allowlist");
                }
            }
            default -> throw invalid("record-mode");
        };
    }

    private static DecisionProperties readProperties(Environment environment) {
        Binder binder = Binder.get(environment);
        DecisionProperties result = bind(binder, PREFIX, DecisionProperties.class, DecisionProperties::new);
        validateCommon(result);

        if ("typesafe".equals(result.getProvider())) {
            DecisionProperties.Typesafe selected = bind(
                    binder, PREFIX + ".typesafe", DecisionProperties.Typesafe.class, DecisionProperties.Typesafe::new);
            validateTypesafe(selected);
            result.select(selected);
        } else if ("prompted".equals(result.getProvider())) {
            DecisionProperties.Prompted selected = bind(
                    binder, PREFIX + ".prompted", DecisionProperties.Prompted.class, DecisionProperties.Prompted::new);
            validatePrompted(selected);
            result.select(selected);
        }
        return result;
    }

    private static <T> T bind(Binder binder, String prefix, Class<T> type, Supplier<T> fallback) {
        try {
            return binder.bind(prefix, Bindable.of(type)).orElseGet(fallback);
        } catch (BindException failure) {
            String name = failure.getName().toString();
            String suffix = name.startsWith(PREFIX + ".") ? name.substring(PREFIX.length() + 1) : name;
            throw invalid(suffix);
        }
    }

    private static void validateCommon(DecisionProperties properties) {
        if (!Set.of("typesafe", "prompted", "none").contains(properties.getProvider())) throw invalid("provider");
        positive(properties.getDefaultTimeout(), "default-timeout");
        if (!Set.of("none", "metadata", "full").contains(properties.getRecordMode())) throw invalid("record-mode");
        try {
            DecisionRecordPolicy.full(properties.getFullRecordMaxBytes(), Set.of("answerIds"));
        } catch (RuntimeException ignored) {
            throw invalid("full-record-max-bytes");
        }
        if (!properties.getRecordAllowlist().isEmpty()) {
            try {
                DecisionRecordPolicy.full(2, properties.getRecordAllowlist());
            } catch (RuntimeException ignored) {
                throw invalid("record-allowlist");
            }
        } else if ("full".equals(properties.getRecordMode())) {
            throw invalid("record-allowlist");
        }
        optionalName(properties.getMapperBeanName(), "mapper-bean-name");
    }

    private static void validateTypesafe(DecisionProperties.Typesafe properties) {
        requiredName(properties.getModel(), "typesafe.model");
        positive(properties.getConnectTimeout(), "typesafe.connect-timeout");
        if (!TypeSafeDecisionModel.supportsBaseUri(properties.getBaseUrl())) throw invalid("typesafe.base-url");
    }

    private static void validatePrompted(DecisionProperties.Prompted properties) {
        requiredName(properties.getLlmBeanName(), "prompted.llm-bean-name");
        optionalName(properties.getOptionsBeanName(), "prompted.options-bean-name");
    }

    private static void positive(Duration duration, String key) {
        try {
            duration.toNanos();
            if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException();
        } catch (RuntimeException ignored) {
            throw invalid(key);
        }
    }

    private static void requiredName(String value, String key) {
        if (value == null || value.isBlank()) throw invalid(key);
    }

    private static void optionalName(String value, String key) {
        if (value != null && value.isBlank()) throw invalid(key);
    }

    private static IllegalStateException invalid(String suffix) {
        return new IllegalStateException("Invalid configuration: " + PREFIX + "." + suffix);
    }
}
