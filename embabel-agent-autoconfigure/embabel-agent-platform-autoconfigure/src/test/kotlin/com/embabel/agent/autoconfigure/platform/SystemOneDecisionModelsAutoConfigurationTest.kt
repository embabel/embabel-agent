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
package com.embabel.agent.autoconfigure.platform

import com.embabel.common.ai.decision.*
import com.embabel.common.ai.decision.systemone.*
import com.embabel.common.ai.model.DecisionModelProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import java.net.URI

class SystemOneDecisionModelsAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
        SystemOneDecisionModelsAutoConfiguration::class.java, DecisionModelServicesAutoConfiguration::class.java))

    @Test
    fun `disabled or absent classes neither configure nor initialize a provider`() {
        runner.run { context -> assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(SystemOneModelFactory::class.java) }
        runner.withClassLoader(FilteredClassLoader("com.embabel.common.ai.decision.systemone"))
            .withPropertyValues("embabel.agent.platform.models.systemone.enabled=true")
            .run { context -> assertThat(context).hasNotFailed() }
        runner.withPropertyValues("embabel.agent.platform.models.systemone.enabled=false", "embabel.agent.platform.models.systemone.base-url=not valid")
            .withInitializer { context -> (context.beanFactory as BeanDefinitionRegistry).registerBeanDefinition("unused",
                BeanDefinitionBuilder.genericBeanDefinition(SystemOneModelFactory::class.java) { error("must stay lazy") }
                    .setLazyInit(true).beanDefinition) }
            .run { context -> assertThat(context).hasNotFailed(); assertThat(context.beanFactory.containsSingleton("unused")).isFalse() }
    }

    @Test
    fun `opt in generic construction is local and tagged settings bind`() {
        // tag::systemone-configuration[]
        runner.withPropertyValues(
            "embabel.agent.platform.models.systemone.enabled=true",
            "embabel.agent.platform.models.systemone.base-url=http://127.0.0.1:1/model-host/",
            "embabel.agent.platform.models.systemone.model-names[0]=alias",
            "embabel.agent.platform.decisions.services.local.provider=systemone",
            "embabel.agent.platform.decisions.services.local.model=alias",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean("local", DecisionService::class.java).name).isEqualTo("alias")
            assertThat(context.getBean(DecisionModelProvider::class.java).models().map { it.modelName }).containsExactly("alias")
        }
        // end::systemone-configuration[]
    }

    @Test
    fun `application factory backs off without reading invalid endpoint or credential`() {
        val custom = SystemOneModelFactory("systemone", "Custom", SystemOneClientOptions(URI("http://127.0.0.1:1/")), listOf("alias"))
        custom.use { runner.withPropertyValues("embabel.agent.platform.models.systemone.enabled=true",
            "embabel.agent.platform.models.systemone.base-url=invalid PRIVATE", "embabel.agent.platform.models.systemone.api-key=PRIVATE")
            .withBean(SystemOneModelFactory::class.java, { custom }).run { context ->
                assertThat(context).hasNotFailed(); assertThat(context.getBean(SystemOneModelFactory::class.java)).isSameAs(custom)
            } }
    }

    @Test
    fun `enabled invalid settings fail instead of silently constructing defaults`() {
        listOf("timeout=0s", "max-response-bytes=0", "max-request-bytes=1048577", "base-url=http://user:PRIVATE@localhost/",
            "model-names[0]= ", "provider-id= ", "unknown=PRIVATE")
            .forEach { setting -> runner.withPropertyValues("embabel.agent.platform.models.systemone.enabled=true",
                "embabel.agent.platform.models.systemone.base-url=http://127.0.0.1:1/",
                "embabel.agent.platform.models.systemone.model-names[0]=alias",
                "embabel.agent.platform.models.systemone.$setting")
                .run { context -> assertThat(context).hasFailed() } }
    }
}
