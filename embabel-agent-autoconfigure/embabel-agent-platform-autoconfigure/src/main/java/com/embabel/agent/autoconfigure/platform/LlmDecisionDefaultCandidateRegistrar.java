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

import com.embabel.agent.spi.PlaceholderLlmService;
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

/**
 * Registers a prompted decision service over the platform's default LLM, and offers it as the
 * decision and classification family default, when the context has no other decision service.
 *
 * <p>It acts only on an otherwise empty registry: no decision or classification service bean and no
 * default candidate bean is defined, and both the {@link LlmDecisionServiceFactory} and a
 * {@link ModelProvider} are. Bean types are read from their definitions, so nothing is created to
 * decide. Services configured under {@code embabel.agent.platform.decisions.llm.services} or the
 * TypeSafe {@code services} are registered by their own post-processors. Like them, this one is not
 * {@code Ordered}: Spring runs ordered post-processors first, which would put this one ahead of them.
 * Among unordered ones Spring keeps registration order, and the auto-configuration ordering registers
 * this one last.
 *
 * <p>The model is resolved once, when the service bean is created, the same way a configured
 * prompted service resolves its model. A placeholder model, standing in when no chat model is
 * configured yet, is accepted and logged at WARN. The service keeps that placeholder if a real model
 * arrives later. A model supplied per user still reaches a decision through {@code using(service)}.
 */
public final class LlmDecisionDefaultCandidateRegistrar implements BeanDefinitionRegistryPostProcessor {

    /** Bean name of the prompted fallback service. */
    public static final String DEFAULT_SERVICE = "llmDefaultDecisionService";

    /** Bean name of the default candidate that offers the fallback service. */
    public static final String CANDIDATE = "llmDefaultDecisionCandidate";

    static final String PROPERTY = "embabel.agent.platform.decisions.llm.default-candidate";

    private static final Logger logger = LoggerFactory.getLogger(LlmDecisionDefaultCandidateRegistrar.class);

    private final BeanFactory beanFactory;

    LlmDecisionDefaultCandidateRegistrar(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (!(beanFactory instanceof ListableBeanFactory beans)
                || defines(beans, ClassificationService.class)
                || defines(beans, DecisionServiceRegistry.DefaultCandidate.class)
                || !defines(beans, LlmDecisionServiceFactory.class)
                || !defines(beans, ModelProvider.class)) {
            return;
        }
        registry.registerBeanDefinition(
                DEFAULT_SERVICE,
                BeanDefinitionBuilder.genericBeanDefinition(DecisionService.class, this::build)
                        .setLazyInit(false)
                        .getBeanDefinition());
        registry.registerBeanDefinition(
                CANDIDATE,
                BeanDefinitionBuilder.genericBeanDefinition(
                                DecisionServiceRegistry.DefaultCandidate.class,
                                () -> new DecisionServiceRegistry.DefaultCandidate(DEFAULT_SERVICE))
                        .setLazyInit(false)
                        .getBeanDefinition());
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }

    /**
     * Whether any bean of this type is defined. Reads declared types only, so no bean is created.
     *
     * @param beans the bean factory to look in
     * @param type the type to look for
     * @return true if at least one bean name has the type
     */
    private static boolean defines(ListableBeanFactory beans, Class<?> type) {
        return beans.getBeanNamesForType(type, true, false).length > 0;
    }

    /**
     * Builds the fallback service over the default LLM.
     *
     * @return the prompted service
     */
    private DecisionService build() {
        var factory = beanFactory.getBean(LlmDecisionServiceFactory.class);
        var llm = beanFactory.getBean(ModelProvider.class).getLlm(DefaultModelSelectionCriteria.INSTANCE);
        if (llm instanceof PlaceholderLlmService) {
            logger.warn(
                    "Decision and classification family default is backed by a placeholder model '{}', because no "
                            + "chat model is configured. It keeps that model after one is configured; restart to use "
                            + "it, or set {}=false to turn this off",
                    llm.getName(), PROPERTY);
        } else {
            logger.info(
                    "Decision and classification family default is the prompted service '{}' over LLM '{}'; "
                            + "set {}=false to turn this off",
                    DEFAULT_SERVICE, llm.getName(), PROPERTY);
        }
        return factory.decisionService(llm);
    }
}
