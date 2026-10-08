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
 * this one last. The check reads declared bean types without creating beans, so a {@code FactoryBean}
 * or a {@code @Bean} method declared as {@code Object} is not seen; an application that defines its
 * decision service that way should set {@code embabel.agent.platform.decisions.llm.default-candidate}
 * to {@code false}.
 *
 * <p>The model is resolved through the model provider on every call, the way the platform resolves
 * the default chat model, so a model configured after startup is used without a restart. A placeholder
 * model standing in at startup, when no chat model is configured yet, is logged at WARN. A model
 * supplied per user still reaches a decision through {@code using(service)}.
 */
public final class LlmDecisionDefaultCandidateRegistrar implements BeanDefinitionRegistryPostProcessor {

    /** Bean name of the prompted default service. */
    public static final String DEFAULT_SERVICE = "llmDefaultDecisionService";

    /** Bean name of the default candidate that offers it. */
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

    /**
     * Whether any bean of this type is defined, read from declared types without creating a bean.
     *
     * @param beans the bean factory to look in
     * @param type the type to look for
     * @return true if at least one bean name has the type
     */
    private static boolean defines(ListableBeanFactory beans, Class<?> type) {
        return beans.getBeanNamesForType(type, true, false).length > 0;
    }

    /**
     * Builds the default service, which asks for the default LLM on every call. The LLM looked up
     * here only names the model in the startup log line.
     *
     * @return the prompted service
     * @throws IllegalStateException when there is not exactly one {@link ModelProvider} or one
     *     {@link LlmDecisionServiceFactory} bean
     */
    private DecisionService build() {
        var factory = unique(LlmDecisionServiceFactory.class);
        var llm = unique(ModelProvider.class).getLlm(DefaultModelSelectionCriteria.INSTANCE);
        if (llm instanceof PlaceholderLlmService) {
            logger.warn(
                    "Decision and classification family default has no chat model yet and is backed by the "
                            + "placeholder '{}'; it uses the default model as soon as one is configured, or unset {} "
                            + "to turn this off",
                    llm.getName(), PROPERTY);
        } else {
            logger.info(
                    "Decision and classification family default is the prompted service '{}' over LLM '{}', "
                            + "turned on by {}=true",
                    DEFAULT_SERVICE, llm.getName(), PROPERTY);
        }
        return factory.decisionService(DefaultModelSelectionCriteria.INSTANCE);
    }

    /**
     * Returns the one bean of this type.
     *
     * @param type the bean type
     * @return the bean
     * @throws IllegalStateException when there is no bean of the type or more than one
     */
    private <T> T unique(Class<T> type) {
        T bean = beanFactory.getBeanProvider(type).getIfUnique();
        if (bean == null) {
            throw new IllegalStateException(PROPERTY + "=true needs exactly one " + ModelProvider.class.getSimpleName()
                    + " and one " + LlmDecisionServiceFactory.class.getSimpleName() + " bean, but the "
                    + type.getSimpleName() + " bean is missing or ambiguous");
        }
        return bean;
    }
}
