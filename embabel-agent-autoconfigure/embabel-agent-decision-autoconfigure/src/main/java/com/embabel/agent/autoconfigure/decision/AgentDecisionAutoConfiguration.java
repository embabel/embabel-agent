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
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
        var selected = properties.getTypesafe();
        if (!validBaseUrl(selected.getBaseUrl())) throw invalid("typesafe.base-url");
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
        String serviceName = properties.getPrompted().getLlmBeanName();
        LlmService service = exactBean(beanFactory, serviceName, LlmService.class, "prompted.llm-bean-name");
        String optionsName = properties.getPrompted().getOptionsBeanName();
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
        DecisionProperties result = new DecisionProperties();
        result.setEnabled(true);
        result.setProvider(requiredSelector(environment, "provider", Set.of("typesafe", "prompted", "none")));
        result.setDefaultTimeout(duration(environment, "default-timeout", Duration.ofSeconds(30), true));
        result.setRecordMode(selector(environment, "record-mode", "metadata", Set.of("none", "metadata", "full")));
        result.setFullRecordMaxBytes(integer(environment, "full-record-max-bytes", 65536, 1, 1048576));
        result.setRecordAllowlist(allowlist(environment.getProperty(PREFIX + ".record-allowlist")));
        result.setMapperBeanName(optionalName(environment, "mapper-bean-name"));

        if ("typesafe".equals(result.getProvider())) {
            result.getTypesafe().setModel(requiredName(environment, "typesafe.model"));
            result.getTypesafe().setBaseUrl(uri(environment, "typesafe.base-url", URI.create("https://api.typesafe.ai")));
            result.getTypesafe().setConnectTimeout(duration(environment, "typesafe.connect-timeout", Duration.ofSeconds(10), true));
        } else if ("prompted".equals(result.getProvider())) {
            result.getPrompted().setLlmBeanName(requiredName(environment, "prompted.llm-bean-name"));
            result.getPrompted().setOptionsBeanName(optionalName(environment, "prompted.options-bean-name"));
        }
        return result;
    }

    private static String requiredSelector(Environment env, String key, Set<String> allowed) {
        String value = env.getProperty(PREFIX + "." + key);
        if (value == null || !allowed.contains(value)) throw invalid(key);
        return value;
    }

    private static String selector(Environment env, String key, String fallback, Set<String> allowed) {
        String value = env.getProperty(PREFIX + "." + key, fallback);
        if (!allowed.contains(value)) throw invalid(key);
        return value;
    }

    private static String requiredName(Environment env, String key) {
        String value = env.getProperty(PREFIX + "." + key);
        if (value == null || value.isBlank()) throw invalid(key);
        return value;
    }

    private static String optionalName(Environment env, String key) {
        String value = env.getProperty(PREFIX + "." + key);
        if (value == null) return null;
        if (value.isBlank()) throw invalid(key);
        return value;
    }

    private static Duration duration(Environment env, String key, Duration fallback, boolean positive) {
        String raw = env.getProperty(PREFIX + "." + key);
        if (raw == null) return fallback;
        try {
            Duration duration = DurationStyle.detectAndParse(raw);
            duration.toNanos();
            if (positive && (duration.isZero() || duration.isNegative())) throw new IllegalArgumentException();
            return duration;
        } catch (RuntimeException ignored) {
            throw invalid(key);
        }
    }

    private static int integer(Environment env, String key, int fallback, int minimum, int maximum) {
        String raw = env.getProperty(PREFIX + "." + key);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException ignored) {
            throw invalid(key);
        }
    }

    private static URI uri(Environment env, String key, URI fallback) {
        String raw = env.getProperty(PREFIX + "." + key);
        if (raw == null) return fallback;
        try {
            return URI.create(raw);
        } catch (RuntimeException ignored) {
            throw invalid(key);
        }
    }

    private static boolean validBaseUrl(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) return false;
        if (!(uri.getPath().isEmpty() || uri.getPath().equals("/"))) return false;
        if (uri.getScheme().equalsIgnoreCase("https")) return true;
        return uri.getScheme().equalsIgnoreCase("http")
                && (uri.getHost().equals("127.0.0.1") || uri.getHost().equals("::1"));
    }

    private static Set<String> allowlist(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(raw.split(",", -1)).map(String::trim).forEach(value -> {
            if (value.isBlank() || value.contains("*")) throw invalid("record-allowlist");
            result.add(value);
        });
        return result;
    }

    private static IllegalStateException invalid(String suffix) {
        return new IllegalStateException("Invalid configuration: " + PREFIX + "." + suffix);
    }
}
