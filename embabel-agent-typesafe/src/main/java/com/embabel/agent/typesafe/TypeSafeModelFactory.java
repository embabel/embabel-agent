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
package com.embabel.agent.typesafe;

import static com.embabel.common.byok.BlankApiKeyKt.requireUsableApiKey;

import com.embabel.common.ai.model.DecisionService;
import com.embabel.common.ai.model.observation.ObservedDecisionService;
import com.embabel.common.byok.ByokFactory;
import com.embabel.common.byok.InvalidApiKeyException;

import io.micrometer.observation.ObservationRegistry;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Builds Embabel decision services backed by TypeSafe models.
 *
 * <p>Provider settings and credentials belong to the factory; {@link #build(String)} selects a
 * model. Each returned service also implements classification because {@link DecisionService}
 * extends the narrower classification contract. The native SDK client remains an implementation
 * detail, so callers retain provider-neutral requests, results, metadata and observability.
 *
 * <p>{@link #buildValidated()} follows the same BYOK contract as the OpenAI and Anthropic
 * factories. Ordinary build calls remain local and do not resolve credentials or contact the
 * provider.
 */
@ApiStatus.Experimental
public class TypeSafeModelFactory implements ByokFactory<DecisionService> {
    /** Provider name reported by every service and result produced by this factory. */
    public static final String PROVIDER = "TypeSafe";

    /** TypeSafe's default Jev model alias. */
    public static final String DEFAULT_MODEL = "jev-latest";

    private static final Logger logger = LoggerFactory.getLogger(TypeSafeModelFactory.class);

    private final TypeSafeClientFactory clients;
    private final Supplier<String> keySupplier;
    private final String defaultModel;
    private final ObservationRegistry observationRegistry;

    /**
     * Uses provider defaults, the fallback transport and a no-op observation registry.
     *
     * @param keySupplier credential source evaluated for each request
     */
    public TypeSafeModelFactory(Supplier<String> keySupplier) {
        this(keySupplier, DEFAULT_MODEL);
    }

    /**
     * Uses the fallback transport with an explicit default model.
     *
     * @param keySupplier credential source evaluated for each request
     * @param defaultModel model returned by {@link #build()} and {@link #buildValidated()}
     */
    public TypeSafeModelFactory(Supplier<String> keySupplier, String defaultModel) {
        this(TypeSafeClientOptions.defaults(), keySupplier, null, ObservationRegistry.NOOP,
                defaultModel);
    }

    /**
     * Uses configured transport bounds with the default model and no-op observations.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     */
    public TypeSafeModelFactory(TypeSafeClientOptions options, Supplier<String> keySupplier) {
        this(options, keySupplier, null);
    }

    /**
     * Uses an application HTTP builder with the default model and no-op logical observations.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     * @param restClientBuilder application builder to clone, or null for the fallback transport
     */
    public TypeSafeModelFactory(TypeSafeClientOptions options, Supplier<String> keySupplier,
            RestClient.@Nullable Builder restClientBuilder) {
        this(options, keySupplier, restClientBuilder, ObservationRegistry.NOOP);
    }

    /**
     * Configures the guarded provider boundary for the default model.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     * @param restClientBuilder application builder to clone, or null for the fallback transport
     * @param observationRegistry registry for framework, provider and fallback HTTP observations
     */
    public TypeSafeModelFactory(TypeSafeClientOptions options, Supplier<String> keySupplier,
            RestClient.@Nullable Builder restClientBuilder, ObservationRegistry observationRegistry) {
        this(options, keySupplier, restClientBuilder, observationRegistry, DEFAULT_MODEL);
    }

    /**
     * Configures the guarded provider boundary shared by services built by this factory.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     * @param restClientBuilder application builder to clone, or null for the fallback transport
     * @param observationRegistry registry for framework, provider and fallback HTTP observations
     * @param defaultModel model returned by {@link #build()} and {@link #buildValidated()}
     */
    public TypeSafeModelFactory(TypeSafeClientOptions options, Supplier<String> keySupplier,
            RestClient.@Nullable Builder restClientBuilder, ObservationRegistry observationRegistry,
            String defaultModel) {
        this.keySupplier = Objects.requireNonNull(keySupplier, "keySupplier");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.clients = new TypeSafeClientFactory(options, keySupplier, restClientBuilder,
                observationRegistry);
        logger.info(
                "TypeSafe model factory initialized: transport={}, observations={}",
                restClientBuilder == null ? "fallback" : "application",
                observationRegistry.isNoop() ? "noop" : "enabled");
    }

    /**
     * Returns the registry shared by this factory's services.
     *
     * @return configured observation registry
     */
    protected final ObservationRegistry getObservationRegistry() {
        return observationRegistry;
    }

    /**
     * Builds a decision service using this factory's default model.
     *
     * @return observed decision service for the configured default
     */
    public final DecisionService build() {
        return build(defaultModel);
    }

    /**
     * Builds a decision service for one model identifier or alias.
     *
     * <p>Services share the factory's guarded transport while keeping independent model metadata.
     * The returned object can be injected as either {@link DecisionService} or its classification
     * supertype.
     *
     * @param model model identifier or alias
     * @return observed decision service for the requested model
     */
    public final DecisionService build(String model) {
        var service = new ObservedDecisionService(new TypeSafeDecisionService(clients.build(model)),
                observationRegistry);
        logger.debug("TypeSafe decision service built: model.selection={}",
                defaultModel.equals(model) ? "default" : "explicit");
        return service;
    }

    /**
     * Validates the current credential against the provider and returns the default model service.
     * Provider failures retain the sanitized exception from the guarded transport as their cause.
     * Credential-supplier failures are logged by exception type only because their messages and
     * causes may contain secrets.
     *
     * @return observed decision service after credential validation
     * @throws InvalidApiKeyException if the credential cannot be validated
     */
    @Override
    public DecisionService buildValidated() {
        logger.debug("TypeSafe credential validation started");
        validateCredentialSource();
        try {
            clients.validate(defaultModel);
        } catch (CancellationException cancelled) {
            logger.debug("TypeSafe credential validation cancelled");
            throw cancelled;
        } catch (TypeSafeException failure) {
            // The guarded transport has already removed credentials and response content.
            logger.warn("TypeSafe provider credential validation failed", failure);
            var invalidKey = new InvalidApiKeyException("TypeSafe credential could not be validated");
            invalidKey.initCause(failure);
            throw invalidKey;
        } catch (RuntimeException failure) {
            // Application callbacks can contain credentials; log the type without wrapping.
            logger.warn("TypeSafe provider validation failed: exception.type={}",
                    failure.getClass().getName());
            throw new InvalidApiKeyException("TypeSafe credential could not be validated");
        }
        logger.info("TypeSafe credential validation succeeded");
        return build();
    }

    /**
     * Reject unusable credentials before contacting the provider. Supplier failures are outside
     * the guarded transport, so log their type without retaining potentially secret exception data.
     */
    private void validateCredentialSource() {
        try {
            requireUsableApiKey(keySupplier.get());
        } catch (CancellationException cancelled) {
            logger.debug("TypeSafe credential resolution cancelled");
            throw cancelled;
        } catch (RuntimeException failure) {
            // Supplier exceptions can contain credentials; log the type without wrapping.
            logger.warn("TypeSafe credential resolution failed: exception.type={}",
                    failure.getClass().getName());
            throw new InvalidApiKeyException("TypeSafe credential could not be validated");
        }
    }
}
