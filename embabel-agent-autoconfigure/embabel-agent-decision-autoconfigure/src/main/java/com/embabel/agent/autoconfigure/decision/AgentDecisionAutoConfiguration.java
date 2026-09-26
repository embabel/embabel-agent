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

import com.embabel.agent.decision.api.DecisionModel;
import com.embabel.agent.decision.api.DecisionInstrumentation;
import com.embabel.agent.decision.api.DecisionModelInitialization;
import com.embabel.agent.decision.api.DecisionRecordPolicy;
import com.embabel.agent.decision.api.NoDecisionModel;
import com.embabel.agent.decision.api.llm.PromptedDecisionModel;
import com.embabel.agent.decision.api.typesafe.TypeSafeDecisionModel;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import org.jetbrains.annotations.ApiStatus;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    static final String PREFIX = "embabel.agent.platform.decision";
    private static final String DEFAULT_TIMEOUT_SUFFIX = ".default-timeout";
    private static final String RECORD_MODE_SUFFIX = ".record-mode";
    private static final String FULL_RECORD_MAX_BYTES_SUFFIX = ".full-record-max-bytes";
    private static final String RECORD_ALLOWLIST_SUFFIX = ".record-allowlist";
    private static final String MAPPER_BEAN_NAME_SUFFIX = ".mapper-bean-name";
    private static final String MODELS_SUFFIX = ".models";
    private static final String PROVIDER_SUFFIX = ".provider";
    private static final String TYPESAFE_BASE_URL_SUFFIX = ".typesafe.base-url";
    private static final String TYPESAFE_CONNECT_TIMEOUT_SUFFIX = ".typesafe.connect-timeout";
    private static final String PROMPTED_LLM_BEAN_NAME_SUFFIX = ".prompted.llm-bean-name";
    private static final String PROMPTED_OPTIONS_BEAN_NAME_SUFFIX = ".prompted.options-bean-name";
    private static final String TYPESAFE_PROVIDER = "typesafe";
    private static final String METADATA_RECORD_MODE = "metadata";
    private static final String MODELS_PREFIX = PREFIX + MODELS_SUFFIX + ".";
    private static final Set<String> GLOBAL_KEYS = Set.of(
            PREFIX + ".enabled",
            PREFIX + DEFAULT_TIMEOUT_SUFFIX,
            PREFIX + RECORD_MODE_SUFFIX,
            PREFIX + FULL_RECORD_MAX_BYTES_SUFFIX,
            PREFIX + RECORD_ALLOWLIST_SUFFIX,
            PREFIX + MAPPER_BEAN_NAME_SUFFIX,
            PREFIX + MODELS_SUFFIX);
    private static final List<String> MODEL_SUFFIXES = List.of(
            PROVIDER_SUFFIX,
            ".typesafe.model",
            TYPESAFE_BASE_URL_SUFFIX,
            TYPESAFE_CONNECT_TIMEOUT_SUFFIX,
            PROMPTED_LLM_BEAN_NAME_SUFFIX,
            PROMPTED_OPTIONS_BEAN_NAME_SUFFIX);
    private static final Map<String, String> RELAXED_GLOBAL_KEYS = Map.ofEntries(
            Map.entry(PREFIX + ".defaulttimeout", PREFIX + DEFAULT_TIMEOUT_SUFFIX),
            Map.entry(PREFIX + ".default.timeout", PREFIX + DEFAULT_TIMEOUT_SUFFIX),
            Map.entry(PREFIX + ".recordmode", PREFIX + RECORD_MODE_SUFFIX),
            Map.entry(PREFIX + ".record.mode", PREFIX + RECORD_MODE_SUFFIX),
            Map.entry(PREFIX + ".fullrecordmaxbytes", PREFIX + FULL_RECORD_MAX_BYTES_SUFFIX),
            Map.entry(PREFIX + ".full.record.max.bytes", PREFIX + FULL_RECORD_MAX_BYTES_SUFFIX),
            Map.entry(PREFIX + ".recordallowlist", PREFIX + RECORD_ALLOWLIST_SUFFIX),
            Map.entry(PREFIX + ".record.allowlist", PREFIX + RECORD_ALLOWLIST_SUFFIX),
            Map.entry(PREFIX + ".mapperbeanname", PREFIX + MAPPER_BEAN_NAME_SUFFIX),
            Map.entry(PREFIX + ".mapper.bean.name", PREFIX + MAPPER_BEAN_NAME_SUFFIX));
    private static final Map<String, String> RELAXED_MODEL_SUFFIXES = Map.ofEntries(
            Map.entry(".typesafe.baseurl", TYPESAFE_BASE_URL_SUFFIX),
            Map.entry(".typesafe.base.url", TYPESAFE_BASE_URL_SUFFIX),
            Map.entry(".typesafe.connecttimeout", TYPESAFE_CONNECT_TIMEOUT_SUFFIX),
            Map.entry(".typesafe.connect.timeout", TYPESAFE_CONNECT_TIMEOUT_SUFFIX),
            Map.entry(".prompted.llmbeanname", PROMPTED_LLM_BEAN_NAME_SUFFIX),
            Map.entry(".prompted.llm.bean.name", PROMPTED_LLM_BEAN_NAME_SUFFIX),
            Map.entry(".prompted.optionsbeanname", PROMPTED_OPTIONS_BEAN_NAME_SUFFIX),
            Map.entry(".prompted.options.bean.name", PROMPTED_OPTIONS_BEAN_NAME_SUFFIX));

    @Bean
    DecisionProperties decisionProperties(Environment environment) {
        return readProperties(environment);
    }

    @Bean
    DecisionModelInitialization decisionModelInitialization(
            DecisionProperties properties,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<DecisionInstrumentation> instrumentationProvider,
            // Intentional ordering dependency: dynamic LLM beans must exist before prompted lookup.
            List<ProviderInitialization> providerInitializations) {
        Map<String, DecisionProperties.Model> configured = properties.models();
        configured.keySet().forEach(name -> rejectCollision(beanFactory, name));

        Map<String, DecisionModel> created = new LinkedHashMap<>();
        try {
            for (var entry : configured.entrySet()) {
                String name = entry.getKey();
                DecisionProperties.Model selected = entry.getValue();
                created.put(name, configuredModel(
                        name, selected, properties, environment, beanFactory,
                        instrumentationProvider.getIfUnique(DecisionInstrumentation::noop)));
            }
            registerModels(beanFactory, created);
            return new DecisionModelInitialization(new ArrayList<>(created.values()));
        } catch (RuntimeException failure) {
            closeAll(created.values(), failure);
            throw failure;
        }
    }

    private static void registerModels(
            ConfigurableListableBeanFactory beanFactory,
            Map<String, DecisionModel> created) {
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
    }

    private DecisionModel configuredModel(
            String name,
            DecisionProperties.Model selected,
            DecisionProperties common,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            DecisionInstrumentation instrumentation) {
        try (DecisionModel model = switch (selected.provider()) {
            case "none" -> NoDecisionModel.create();
            case TYPESAFE_PROVIDER -> typesafe(selected, common, environment, beanFactory);
            case "prompted" -> prompted(name, selected, common, beanFactory);
            default -> throw invalid(modelKey(name, "provider"));
        }; DecisionModel defaulted = model.withDefaults(common.defaultTimeout(), recordPolicy(common))) {
            DecisionModel named = defaulted.named(name, selected.provider());
            named.installDefaultInstrumentation(instrumentation);
            return named;
        }
    }

    private static void rejectCollision(ConfigurableListableBeanFactory beanFactory, String name) {
        if (beanFactory.containsBean(name)
                || (beanFactory instanceof AliasRegistry aliases && aliases.isAlias(name))
                || beanFactory.containsBeanDefinition(name)
                || beanFactory.containsSingleton(name)) {
            throw invalid(MODELS_PREFIX + name);
        }
    }

    private DecisionModel typesafe(
            DecisionProperties.Model model,
            DecisionProperties common,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory) {
        DecisionProperties.Typesafe selected = model.typesafe();
        EmbabelObjectMapperHolder mapper = mapper(common, beanFactory);
        Supplier<String> apiKey = () -> {
            String value = environment.getProperty("TYPESAFE_API_KEY");
            return value == null ? "" : value;
        };
        if (mapper == null) {
            return TypeSafeDecisionModel.create(
                    apiKey, selected.model(), selected.baseUrl(), selected.connectTimeout());
        }
        return TypeSafeDecisionModel.create(
                apiKey, selected.model(), selected.baseUrl(), selected.connectTimeout(), mapper);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DecisionModel prompted(
            String name,
            DecisionProperties.Model model,
            DecisionProperties common,
            ConfigurableListableBeanFactory beanFactory) {
        DecisionProperties.Prompted selected = model.prompted();
        LlmService service = exactBean(
                beanFactory, selected.llmBeanName(), LlmService.class, modelKey(name, "prompted.llm-bean-name"));
        LlmOptions options = selected.optionsBeanName() == null
                ? LlmOptions.withDefaults()
                : exactBean(
                        beanFactory,
                        selected.optionsBeanName(),
                        LlmOptions.class,
                        modelKey(name, "prompted.options-bean-name"));
        EmbabelObjectMapperHolder mapper = mapper(common, beanFactory);
        return mapper == null
                ? PromptedDecisionModel.create(service, options)
                : PromptedDecisionModel.create(service, options, mapper);
    }

    private EmbabelObjectMapperHolder mapper(
            DecisionProperties properties, ConfigurableListableBeanFactory beanFactory) {
        String name = properties.mapperBeanName();
        return name == null
                ? null
                : exactBean(beanFactory, name, EmbabelObjectMapperHolder.class, PREFIX + MAPPER_BEAN_NAME_SUFFIX);
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
        return switch (properties.recordMode()) {
            case "none" -> DecisionRecordPolicy.none();
            case METADATA_RECORD_MODE -> DecisionRecordPolicy.metadata();
            case "full" -> {
                try {
                    yield DecisionRecordPolicy.full(
                            properties.fullRecordMaxBytes(), properties.recordAllowlist());
                } catch (RuntimeException ignored) {
                    throw invalid(PREFIX + RECORD_ALLOWLIST_SUFFIX);
                }
            }
            default -> throw invalid(PREFIX + RECORD_MODE_SUFFIX);
        };
    }

    private static DecisionProperties readProperties(Environment environment) {
        validatePropertyNames(environment);
        Binder binder = Binder.get(environment);
        var result = new DecisionProperties(
                value(binder, PREFIX + ".enabled", Boolean.class, () -> false),
                value(binder, PREFIX + DEFAULT_TIMEOUT_SUFFIX, Duration.class, () -> Duration.ofSeconds(30)),
                value(binder, PREFIX + RECORD_MODE_SUFFIX, String.class, () -> METADATA_RECORD_MODE),
                value(binder, PREFIX + FULL_RECORD_MAX_BYTES_SUFFIX, Integer.class, () -> 65536),
                setValue(binder, PREFIX + RECORD_ALLOWLIST_SUFFIX),
                value(binder, PREFIX + MAPPER_BEAN_NAME_SUFFIX, String.class, () -> null),
                Map.of());
        validateCommon(result);
        Map<String, DecisionProperties.Model> models = new LinkedHashMap<>();
        Set<String> configuredNames = modelNames(environment);
        if (configuredNames.isEmpty()) {
            models.put("jev", new DecisionProperties.Model(TYPESAFE_PROVIDER,
                    DecisionProperties.Typesafe.defaults("jev-latest"), null));
        }
        for (String name : configuredNames) {
            validateModelName(name);
            String base = MODELS_PREFIX + name;
            String provider = value(binder, base + PROVIDER_SUFFIX, String.class, () -> null);
            DecisionProperties.Model model = switch (provider) {
                case TYPESAFE_PROVIDER -> {
                    DecisionProperties.Typesafe selected = objectValue(
                            binder, base + ".typesafe", DecisionProperties.Typesafe.class,
                            () -> DecisionProperties.Typesafe.defaults(null));
                    validateTypesafe(name, selected);
                    yield new DecisionProperties.Model(provider, selected, null);
                }
                case "prompted" -> {
                    DecisionProperties.Prompted selected = objectValue(
                            binder, base + ".prompted", DecisionProperties.Prompted.class,
                            () -> new DecisionProperties.Prompted(null, null));
                    validatePrompted(name, selected);
                    yield new DecisionProperties.Model(provider, null, selected);
                }
                case "none" -> new DecisionProperties.Model(provider, null, null);
                case null, default -> throw invalid(base + PROVIDER_SUFFIX);
            };
            models.put(name, model);
        }
        return new DecisionProperties(result.enabled(), result.defaultTimeout(), result.recordMode(),
                result.fullRecordMaxBytes(), result.recordAllowlist(), result.mapperBeanName(), models);
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
        return key.equals(PREFIX + PROVIDER_SUFFIX)
                || key.startsWith(PREFIX + ".typesafe.")
                || key.startsWith(PREFIX + ".prompted.");
    }

    private static boolean isAllowedKey(String key) {
        if (GLOBAL_KEYS.contains(key) || key.startsWith(PREFIX + RECORD_ALLOWLIST_SUFFIX + "[")) return true;
        if (!key.startsWith(MODELS_PREFIX)) return false;
        String tail = key.substring(MODELS_PREFIX.length());
        return MODEL_SUFFIXES.stream().anyMatch(suffix -> tail.length() > suffix.length() && tail.endsWith(suffix));
    }

    private static void validateCommon(DecisionProperties properties) {
        positive(properties.defaultTimeout(), PREFIX + DEFAULT_TIMEOUT_SUFFIX);
        if (!Set.of("none", METADATA_RECORD_MODE, "full").contains(properties.recordMode())) {
            throw invalid(PREFIX + RECORD_MODE_SUFFIX);
        }
        try {
            DecisionRecordPolicy.full(properties.fullRecordMaxBytes(), Set.of("answerIds"));
        } catch (RuntimeException ignored) {
            throw invalid(PREFIX + FULL_RECORD_MAX_BYTES_SUFFIX);
        }
        if (!properties.recordAllowlist().isEmpty()) {
            try {
                DecisionRecordPolicy.full(2, properties.recordAllowlist());
            } catch (RuntimeException ignored) {
                throw invalid(PREFIX + RECORD_ALLOWLIST_SUFFIX);
            }
        } else if ("full".equals(properties.recordMode())) {
            throw invalid(PREFIX + RECORD_ALLOWLIST_SUFFIX);
        }
        optionalName(properties.mapperBeanName(), PREFIX + MAPPER_BEAN_NAME_SUFFIX);
    }

    private static void validateTypesafe(String name, DecisionProperties.Typesafe properties) {
        requiredName(properties.model(), modelKey(name, "typesafe.model"));
        positive(properties.connectTimeout(), modelKey(name, "typesafe.connect-timeout"));
        if (!supportsAutoConfiguredTypeSafeOrigin(properties.baseUrl())) {
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
        requiredName(properties.llmBeanName(), modelKey(name, "prompted.llm-bean-name"));
        optionalName(properties.optionsBeanName(), modelKey(name, "prompted.options-bean-name"));
    }

    private static void validateModelName(String name) {
        // Dots are property path separators; registry names use canonical lowercase segments.
        if (name == null || name.isEmpty()) throw invalid(PREFIX + MODELS_SUFFIX);
        boolean requiresSegmentCharacter = true;
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character == '-') {
                if (requiresSegmentCharacter) throw invalid(PREFIX + MODELS_SUFFIX);
                requiresSegmentCharacter = true;
            } else if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                requiresSegmentCharacter = false;
            } else {
                throw invalid(PREFIX + MODELS_SUFFIX);
            }
        }
        if (requiresSegmentCharacter) throw invalid(PREFIX + MODELS_SUFFIX);
    }

    private static void rollback(
            ConfigurableListableBeanFactory beanFactory,
            List<String> registered,
            Map<String, DecisionModel> created,
            RuntimeException failure) {
        for (String name : registered.reversed()) {
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
            long nanos = duration.toNanos();
            if (nanos <= 0) throw new IllegalArgumentException();
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
