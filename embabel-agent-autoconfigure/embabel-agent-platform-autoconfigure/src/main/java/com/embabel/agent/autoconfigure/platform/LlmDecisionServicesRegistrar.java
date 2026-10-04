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
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.NoSuitableModelException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.core.env.Environment;

import java.util.Map;

/**
 * Registers one {@link DecisionService} bean per configured entry. The platform's
 * {@link LlmDecisionServiceFactory} bean builds each service, so an application that supplies its
 * own factory changes them too.
 *
 * <p>Startup fails if an entry names no LLM or one the model provider doesn't know, or if an entry
 * has a key this class doesn't know. The error names the property. Unknown keys are only caught in
 * configuration files and other property sources. Environment variables and system properties are
 * not checked for them.
 */
public final class LlmDecisionServicesRegistrar implements BeanDefinitionRegistryPostProcessor {

    static final String SERVICES_PREFIX = "embabel.agent.platform.decisions.llm.services";

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

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        services.forEach((key, service) -> registry.registerBeanDefinition(key, definition(key, service)));
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
