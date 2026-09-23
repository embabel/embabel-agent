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
import com.embabel.agent.decision.DecisionModelInitialization;
import com.embabel.agent.decision.DecisionRecordPolicy;
import com.embabel.agent.decision.NoDecisionModel;
import com.embabel.agent.decision.llm.PromptedDecisionModel;
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import org.jetbrains.annotations.ApiStatus;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.AliasRegistry;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Opt-in configuration for named experimental typed decision models. */
@ApiStatus.Experimental
@AutoConfiguration
@ConditionalOnClass(DecisionModel.class)
@ConditionalOnProperty(prefix = AgentDecisionAutoConfiguration.PREFIX, name = "enabled", havingValue = "true")
public class AgentDecisionAutoConfiguration {
    static final String PREFIX = "embabel.agent.decision";
    private static final String MODELS_PREFIX = PREFIX + ".models.";
    private static final Set<String> GLOBAL_KEYS = Set.of(
            PREFIX + ".enabled",
            PREFIX + ".default-timeout",
            PREFIX + ".record-mode",
            PREFIX + ".full-record-max-bytes",
            PREFIX + ".record-allowlist",
            PREFIX + ".mapper-bean-name",
            PREFIX + ".models");
    private static final List<String> MODEL_SUFFIXES = List.of(
            ".provider",
            ".typesafe.model",
            ".typesafe.base-url",
            ".typesafe.connect-timeout",
            ".prompted.llm-bean-name",
            ".prompted.options-bean-name");
    private static final Map<String, String> RELAXED_GLOBAL_KEYS = Map.ofEntries(
            Map.entry(PREFIX + ".defaulttimeout", PREFIX + ".default-timeout"),
            Map.entry(PREFIX + ".default.timeout", PREFIX + ".default-timeout"),
            Map.entry(PREFIX + ".recordmode", PREFIX + ".record-mode"),
            Map.entry(PREFIX + ".record.mode", PREFIX + ".record-mode"),
            Map.entry(PREFIX + ".fullrecordmaxbytes", PREFIX + ".full-record-max-bytes"),
            Map.entry(PREFIX + ".full.record.max.bytes", PREFIX + ".full-record-max-bytes"),
            Map.entry(PREFIX + ".recordallowlist", PREFIX + ".record-allowlist"),
            Map.entry(PREFIX + ".record.allowlist", PREFIX + ".record-allowlist"),
            Map.entry(PREFIX + ".mapperbeanname", PREFIX + ".mapper-bean-name"),
            Map.entry(PREFIX + ".mapper.bean.name", PREFIX + ".mapper-bean-name"));
    private static final Map<String, String> RELAXED_MODEL_SUFFIXES = Map.ofEntries(
            Map.entry(".typesafe.baseurl", ".typesafe.base-url"),
            Map.entry(".typesafe.base.url", ".typesafe.base-url"),
            Map.entry(".typesafe.connecttimeout", ".typesafe.connect-timeout"),
            Map.entry(".typesafe.connect.timeout", ".typesafe.connect-timeout"),
            Map.entry(".prompted.llmbeanname", ".prompted.llm-bean-name"),
            Map.entry(".prompted.llm.bean.name", ".prompted.llm-bean-name"),
            Map.entry(".prompted.optionsbeanname", ".prompted.options-bean-name"),
            Map.entry(".prompted.options.bean.name", ".prompted.options-bean-name"));

    @Bean
    DecisionProperties decisionProperties(Environment environment) {
        return readProperties(environment);
    }

    @Bean
    DecisionModelInitialization decisionModelInitialization(
            DecisionProperties properties,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            // Intentional ordering dependency: dynamic LLM beans must exist before prompted lookup.
            List<ProviderInitialization> providerInitializations) {
        Map<String, DecisionProperties.Model> configured = properties.getModels();
        configured.keySet().forEach(name -> rejectCollision(beanFactory, name));

        Map<String, DecisionModel> created = new LinkedHashMap<>();
        try {
            for (var entry : configured.entrySet()) {
                String name = entry.getKey();
                DecisionProperties.Model selected = entry.getValue();
                DecisionModel model = switch (selected.getProvider()) {
                    case "none" -> NoDecisionModel.create();
                    case "typesafe" -> typesafe(selected, properties, environment, beanFactory);
                    case "prompted" -> prompted(name, selected, properties, beanFactory);
                    default -> throw invalid(modelKey(name, "provider"));
                };
                created.put(name, model
                        .withDefaults(properties.getDefaultTimeout(), recordPolicy(properties))
                        .named(name, selected.getProvider()));
            }
            List<String> registered = new ArrayList<>();
            try {
                created.forEach((name, model) -> {
                    beanFactory.registerSingleton(name, model);
                    registered.add(name);
                });
            } catch (RuntimeException failure) {
                rollback(beanFactory, registered, created, failure);
                throw failure;
            }
            return new DecisionModelInitialization(new ArrayList<>(created.values()));
        } catch (RuntimeException failure) {
            closeAll(created.values(), failure);
            throw failure;
        }
    }

    private static void rejectCollision(ConfigurableListableBeanFactory beanFactory, String name) {
        if (beanFactory.containsBean(name)
                || (beanFactory instanceof AliasRegistry aliases && aliases.isAlias(name))
                || beanFactory.containsBeanDefinition(name)
                || beanFactory.containsSingleton(name)) {
            throw invalid(PREFIX + ".models." + name);
        }
    }

    private DecisionModel typesafe(
            DecisionProperties.Model model,
            DecisionProperties common,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory) {
        DecisionProperties.Typesafe selected = model.getTypesafe();
        EmbabelObjectMapperHolder mapper = mapper(common, beanFactory);
        Supplier<String> apiKey = () -> {
            String value = environment.getProperty("TYPESAFE_API_KEY");
            return value == null ? "" : value;
        };
        if (mapper == null) {
            return TypeSafeDecisionModel.create(
                    apiKey, selected.getModel(), selected.getBaseUrl(), selected.getConnectTimeout());
        }
        return TypeSafeDecisionModel.create(
                apiKey, selected.getModel(), selected.getBaseUrl(), selected.getConnectTimeout(), mapper);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DecisionModel prompted(
            String name,
            DecisionProperties.Model model,
            DecisionProperties common,
            ConfigurableListableBeanFactory beanFactory) {
        DecisionProperties.Prompted selected = model.getPrompted();
        LlmService service = exactBean(
                beanFactory, selected.getLlmBeanName(), LlmService.class, modelKey(name, "prompted.llm-bean-name"));
        LlmOptions options = selected.getOptionsBeanName() == null
                ? LlmOptions.withDefaults()
                : exactBean(
                        beanFactory,
                        selected.getOptionsBeanName(),
                        LlmOptions.class,
                        modelKey(name, "prompted.options-bean-name"));
        EmbabelObjectMapperHolder mapper = mapper(common, beanFactory);
        return mapper == null
                ? PromptedDecisionModel.create(service, options)
                : PromptedDecisionModel.create(service, options, mapper);
    }

    private EmbabelObjectMapperHolder mapper(
            DecisionProperties properties, ConfigurableListableBeanFactory beanFactory) {
        String name = properties.getMapperBeanName();
        return name == null
                ? null
                : exactBean(beanFactory, name, EmbabelObjectMapperHolder.class, PREFIX + ".mapper-bean-name");
    }

    private static <T> T exactBean(
            ConfigurableListableBeanFactory factory, String name, Class<T> type, String key) {
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
                    yield DecisionRecordPolicy.full(
                            properties.getFullRecordMaxBytes(), properties.getRecordAllowlist());
                } catch (RuntimeException ignored) {
                    throw invalid(PREFIX + ".record-allowlist");
                }
            }
            default -> throw invalid(PREFIX + ".record-mode");
        };
    }

    private static DecisionProperties readProperties(Environment environment) {
        validatePropertyNames(environment);
        Binder binder = Binder.get(environment);
        DecisionProperties result = new DecisionProperties();
        result.setEnabled(value(binder, PREFIX + ".enabled", Boolean.class, () -> false));
        result.setDefaultTimeout(value(
                binder, PREFIX + ".default-timeout", Duration.class, () -> Duration.ofSeconds(30)));
        result.setRecordMode(value(binder, PREFIX + ".record-mode", String.class, () -> "metadata"));
        result.setFullRecordMaxBytes(value(
                binder, PREFIX + ".full-record-max-bytes", Integer.class, () -> 65536));
        result.setRecordAllowlist(setValue(binder, PREFIX + ".record-allowlist"));
        result.setMapperBeanName(value(binder, PREFIX + ".mapper-bean-name", String.class, () -> null));
        validateCommon(result);

        Set<String> configuredNames = modelNames(environment);
        if (configuredNames.isEmpty()) {
            DecisionProperties.Model implicit = new DecisionProperties.Model();
            implicit.setProvider("typesafe");
            DecisionProperties.Typesafe selected = new DecisionProperties.Typesafe();
            selected.setModel("jev-latest");
            implicit.select(selected);
            result.addModel("jev", implicit);
        }
        for (String name : configuredNames) {
            validateModelName(name);
            String base = MODELS_PREFIX + name;
            DecisionProperties.Model model = new DecisionProperties.Model();
            model.setProvider(value(binder, base + ".provider", String.class, () -> null));
            switch (model.getProvider() == null ? "" : model.getProvider()) {
                case "typesafe" -> {
                    DecisionProperties.Typesafe selected = objectValue(
                            binder, base + ".typesafe", DecisionProperties.Typesafe.class,
                            DecisionProperties.Typesafe::new);
                    validateTypesafe(name, selected);
                    model.select(selected);
                }
                case "prompted" -> {
                    DecisionProperties.Prompted selected = objectValue(
                            binder, base + ".prompted", DecisionProperties.Prompted.class,
                            DecisionProperties.Prompted::new);
                    validatePrompted(name, selected);
                    model.select(selected);
                }
                case "none" -> { }
                default -> throw invalid(base + ".provider");
            }
            result.addModel(name, model);
        }
        return result;
    }

    private static <T> T value(Binder binder, String key, Class<T> type, Supplier<T> fallback) {
        return objectValue(binder, key, type, fallback);
    }

    private static <T> T objectValue(Binder binder, String key, Class<T> type, Supplier<T> fallback) {
        try {
            return binder.bind(key, Bindable.of(type)).orElseGet(fallback);
        } catch (BindException failure) {
            throw invalid(key);
        }
    }

    private static Set<String> setValue(Binder binder, String key) {
        try {
            return binder.bind(key, Bindable.setOf(String.class)).orElseGet(LinkedHashSet::new);
        } catch (BindException failure) {
            throw invalid(key);
        }
    }

    private static void validatePropertyNames(Environment environment) {
        validateRawModelNames(environment);
        for (String key : decisionPropertyNames(environment)) {
            if (isLegacyKey(key) || !isAllowedKey(key)) throw invalid(key);
        }
    }

    private static Set<String> decisionPropertyNames(Environment environment) {
        Set<String> names = new TreeSet<>();
        for (var source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                iterable.stream()
                        .map(Object::toString)
                        .filter(name -> name.startsWith(PREFIX + "."))
                        .map(AgentDecisionAutoConfiguration::canonicalDecisionKey)
                        .forEach(names::add);
            }
        }
        return names;
    }

    private static String canonicalDecisionKey(String key) {
        String canonical = RELAXED_GLOBAL_KEYS.getOrDefault(key, key);
        for (var alias : RELAXED_GLOBAL_KEYS.entrySet()) {
            if (key.startsWith(alias.getKey() + "[")) {
                canonical = alias.getValue() + key.substring(alias.getKey().length());
                break;
            }
        }
        for (var alias : RELAXED_MODEL_SUFFIXES.entrySet()) {
            if (canonical.endsWith(alias.getKey())) {
                return canonical.substring(0, canonical.length() - alias.getKey().length()) + alias.getValue();
            }
        }
        return canonical;
    }

    private static void validateRawModelNames(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) return;
        configurable.getPropertySources().forEach(source -> {
            if (source instanceof SystemEnvironmentPropertySource) return;
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) return;
            for (String key : enumerable.getPropertyNames()) {
                if (!key.startsWith(MODELS_PREFIX)) continue;
                String tail = key.substring(MODELS_PREFIX.length());
                int separator = tail.indexOf('.');
                if (separator > 0) validateModelName(tail.substring(0, separator));
            }
        });
    }

    private static Set<String> modelNames(Environment environment) {
        Set<String> names = new TreeSet<>();
        for (String key : decisionPropertyNames(environment)) {
            if (!key.startsWith(MODELS_PREFIX)) continue;
            String tail = key.substring(MODELS_PREFIX.length());
            for (String suffix : MODEL_SUFFIXES) {
                if (tail.endsWith(suffix)) {
                    names.add(tail.substring(0, tail.length() - suffix.length()));
                    break;
                }
            }
        }
        return names;
    }

    private static boolean isLegacyKey(String key) {
        return key.equals(PREFIX + ".provider")
                || key.startsWith(PREFIX + ".typesafe.")
                || key.startsWith(PREFIX + ".prompted.");
    }

    private static boolean isAllowedKey(String key) {
        if (GLOBAL_KEYS.contains(key) || key.startsWith(PREFIX + ".record-allowlist[")) return true;
        if (!key.startsWith(MODELS_PREFIX)) return false;
        String tail = key.substring(MODELS_PREFIX.length());
        return MODEL_SUFFIXES.stream().anyMatch(suffix -> tail.length() > suffix.length() && tail.endsWith(suffix));
    }

    private static void validateCommon(DecisionProperties properties) {
        positive(properties.getDefaultTimeout(), PREFIX + ".default-timeout");
        if (!Set.of("none", "metadata", "full").contains(properties.getRecordMode())) {
            throw invalid(PREFIX + ".record-mode");
        }
        try {
            DecisionRecordPolicy.full(properties.getFullRecordMaxBytes(), Set.of("answerIds"));
        } catch (RuntimeException ignored) {
            throw invalid(PREFIX + ".full-record-max-bytes");
        }
        if (!properties.getRecordAllowlist().isEmpty()) {
            try {
                DecisionRecordPolicy.full(2, properties.getRecordAllowlist());
            } catch (RuntimeException ignored) {
                throw invalid(PREFIX + ".record-allowlist");
            }
        } else if ("full".equals(properties.getRecordMode())) {
            throw invalid(PREFIX + ".record-allowlist");
        }
        optionalName(properties.getMapperBeanName(), PREFIX + ".mapper-bean-name");
    }

    private static void validateTypesafe(String name, DecisionProperties.Typesafe properties) {
        requiredName(properties.getModel(), modelKey(name, "typesafe.model"));
        positive(properties.getConnectTimeout(), modelKey(name, "typesafe.connect-timeout"));
        if (!supportsAutoConfiguredTypeSafeOrigin(properties.getBaseUrl())) {
            throw invalid(modelKey(name, "typesafe.base-url"));
        }
    }

    private static void closeAll(Iterable<DecisionModel> models, RuntimeException failure) {
        for (DecisionModel model : models) {
            try {
                model.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void validatePrompted(String name, DecisionProperties.Prompted properties) {
        requiredName(properties.getLlmBeanName(), modelKey(name, "prompted.llm-bean-name"));
        optionalName(properties.getOptionsBeanName(), modelKey(name, "prompted.options-bean-name"));
    }

    private static void validateModelName(String name) {
        // Dots are property path separators; registry names use canonical lowercase segments.
        if (name == null || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid(PREFIX + ".models");
        }
    }

    private static void rollback(
            ConfigurableListableBeanFactory beanFactory,
            List<String> registered,
            Map<String, DecisionModel> created,
            RuntimeException failure) {
        for (int i = registered.size() - 1; i >= 0; i--) {
            String name = registered.get(i);
            try {
                if (beanFactory instanceof DefaultSingletonBeanRegistry registry) {
                    registry.destroySingleton(name);
                } else {
                    beanFactory.destroyBean(name, created.get(name));
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
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

    private static boolean supportsAutoConfiguredTypeSafeOrigin(URI uri) {
        if (!TypeSafeDecisionModel.supportsBaseUri(uri)) return false;
        String host = uri.getHost();
        if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && ("127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host))) return true;
        return "https".equalsIgnoreCase(uri.getScheme())
                && "api.typesafe.ai".equalsIgnoreCase(host)
                && uri.getPort() == -1;
    }

    private static String modelKey(String name, String suffix) {
        return MODELS_PREFIX + name + "." + suffix;
    }

    private static IllegalStateException invalid(String key) {
        return new IllegalStateException("Invalid configuration: " + key);
    }
}
