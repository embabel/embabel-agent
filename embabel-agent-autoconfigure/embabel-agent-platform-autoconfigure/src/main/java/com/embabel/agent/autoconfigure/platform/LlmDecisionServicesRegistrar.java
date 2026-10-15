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
package com.embabel.agent.autoconfigure.platform;

import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.NoSuitableModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.Map;

/**
 * Registers one {@link DecisionService} bean per configured entry. The platform's
 * {@link LlmDecisionServiceFactory} bean builds each service, so an application that supplies its
 * own factory changes them too.
 *
 * <p>An entry whose key names a decision or classification service bean the application defines is
 * skipped, and the application's bean serves that name. Startup fails when the key names a bean of
 * another type, or an entry under {@code embabel.agent.platform.models.typesafe.services} with the
 * same key. The error names both and asks for one key to be renamed.
 *
 * <p>Startup fails if an entry names no LLM or one the model provider doesn't know, or if an entry
 * has a key this class doesn't know. The error names the property. Unknown keys are only caught in
 * configuration files and other property sources. Environment variables and system properties are
 * not checked for them.
 */
public final class LlmDecisionServicesRegistrar implements BeanDefinitionRegistryPostProcessor {

    static final String SERVICES_PREFIX = "embabel.agent.platform.decisions.llm.services";

    /**
     * Bean definition attribute holding the property path of a configured decision service. The
     * TypeSafe services registrar uses the same attribute name.
     */
    public static final String CONFIGURED_SERVICE_ATTRIBUTE = "com.embabel.decision.configuredServiceProperty";

    private static final Logger logger = LoggerFactory.getLogger(LlmDecisionServicesRegistrar.class);

    private final Map<String, Service> services;

    private final BeanFactory beanFactory;

    LlmDecisionServicesRegistrar(Map<String, Service> services, BeanFactory beanFactory) {
        this.services = services;
        this.beanFactory = beanFactory;
    }

    /**
     * One declared service.
     *
     * @param llm name of the model that answers
     */
    record Service(String llm) {
    }

    /**
     * Binds the declared services. A key that no service field matches fails the binding, unless it
     * comes from an environment variable or a system property.
     *
     * @param environment the environment to bind from
     * @return the services by key, empty when none are declared
     */
    static Map<String, Service> bindServices(Environment environment) {
        return Binder.get(environment)
                .bind(
                        SERVICES_PREFIX,
                        Bindable.mapOf(String.class, Service.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT, new UnboundElementsSourceFilter()))
                .orElse(Map.of());
    }

    // Application definitions are registered before this post-processor runs, so an application bean
    // with a configured key is present here. The other configured-service registrar can run before or
    // after this one. Each marks its definitions with the property path, so the one that runs second
    // finds the clash.
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        services.forEach((key, service) -> {
            var property = SERVICES_PREFIX + "." + key;
            if (registry.containsBeanDefinition(key)) {
                requireApplicationService(registry, key, property);
                logger.info("Decision service '{}' is defined by the application; its configured definition is skipped", key);
            } else {
                var definition = definition(key, service);
                definition.setAttribute(CONFIGURED_SERVICE_ATTRIBUTE, property);
                registry.registerBeanDefinition(key, definition);
            }
        });
    }

    /**
     * Fails unless the existing definition is an application decision or classification service. The
     * type comes from the definition, so no bean is created.
     *
     * @param registry the registry that holds the existing definition
     * @param key the configured key the definition shares
     * @param property the configuration property path, used in the error message
     * @throws IllegalStateException if another configured entry uses the key, or the bean is not a service
     */
    private static void requireApplicationService(BeanDefinitionRegistry registry, String key, String property) {
        var existing = registry.getBeanDefinition(key);
        if (existing.getAttribute(CONFIGURED_SERVICE_ATTRIBUTE) instanceof String other) {
            throw new IllegalStateException(
                    property + " and " + other + " both configure a decision service named '" + key
                            + "'. Rename one of the keys.");
        }
        var type = definedType(registry, existing);
        if (type == null || !ClassificationService.class.isAssignableFrom(type)) {
            var described = type == null ? "of a type that cannot be read from its definition" : "of type " + type.getName();
            throw new IllegalStateException(
                    property + " is configured, and the context already has a bean named '" + key + "' " + described
                            + ". A configured key can only share its name with a decision or classification service bean."
                            + " Rename the key under " + SERVICES_PREFIX + ".");
        }
    }

    /**
     * The type a definition declares: the {@code @Bean} method's return type, or else the bean class.
     *
     * @param registry the registry, whose class loader resolves the type
     * @param definition the definition to read
     * @return the declared type, or null when it can't be read
     */
    private static Class<?> definedType(BeanDefinitionRegistry registry, BeanDefinition definition) {
        var classLoader = registry instanceof ConfigurableBeanFactory factory
                ? factory.getBeanClassLoader()
                : ClassUtils.getDefaultClassLoader();
        String typeName = null;
        if (definition instanceof AnnotatedBeanDefinition annotated && annotated.getFactoryMethodMetadata() != null) {
            typeName = annotated.getFactoryMethodMetadata().getReturnTypeName();
        }
        if (typeName == null) {
            var resolved = definition.getResolvableType().resolve();
            typeName = resolved == null ? null : resolved.getName();
        }
        if (typeName == null && definition.getFactoryMethodName() == null) {
            typeName = definition.getBeanClassName();
        }
        if (typeName == null) {
            return null;
        }
        try {
            return ClassUtils.forName(typeName, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Names the service type in the definition so the model provider's search for LLM beans can skip
     * it without creating it. It is eager, so an unknown LLM stops startup even when the application
     * makes beans lazy by default.
     *
     * @param key the service's config key
     * @param service the entry's declared properties
     * @return the bean definition to register
     */
    private BeanDefinition definition(String key, Service service) {
        var llm = service.llm();
        if (llm == null || llm.isBlank()) {
            throw new IllegalStateException(SERVICES_PREFIX + "." + key + ".llm must name an LLM");
        }
        return BeanDefinitionBuilder.genericBeanDefinition(DecisionService.class, () -> build(key, llm))
                .setLazyInit(false)
                .getBeanDefinition();
    }

    /**
     * Builds one service through the factory bean.
     *
     * @param key the service's config key, used in the error message
     * @param llm the LLM name
     * @return the built service
     */
    private DecisionService build(String key, String llm) {
        var factory = beanFactory.getBean(LlmDecisionServiceFactory.class);
        try {
            return factory.decisionService(llm);
        } catch (NoSuitableModelException e) {
            throw new IllegalStateException(SERVICES_PREFIX + "." + key + ".llm names unknown LLM '" + llm + "'", e);
        }
    }
}
