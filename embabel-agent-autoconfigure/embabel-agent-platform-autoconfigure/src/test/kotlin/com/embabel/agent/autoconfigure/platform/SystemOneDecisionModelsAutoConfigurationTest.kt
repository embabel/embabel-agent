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
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.model.DecisionModelProvider
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

    // tag::systemone-configuration[]
    private fun configured(baseUrl: String) = runner
        .withConfiguration(AutoConfigurations.of(DecisionServiceRegistryAutoConfiguration::class.java))
        .withPropertyValues(
            "embabel.agent.platform.models.systemone.enabled=true",
            "embabel.agent.platform.models.systemone.base-url=$baseUrl",
            "embabel.agent.platform.models.systemone.model-names[0]=alias",
            "embabel.agent.platform.models.systemone.timeout=10s",
            "embabel.agent.platform.models.systemone.max-request-bytes=32768",
            "embabel.agent.platform.models.systemone.max-response-bytes=131072",
            "embabel.agent.platform.decisions.services.local.provider=systemone",
            "embabel.agent.platform.decisions.services.local.model=alias",
            "embabel.models.decision.default=local",
            "embabel.models.decision.roles.support-triage=local",
            "embabel.models.classification.default=local",
            "embabel.models.classification.roles.ticket-routing=local",
        )
    // end::systemone-configuration[]

    @Test
    fun `explicit endpoint named service defaults and roles bind without startup HTTP`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            configured("http://127.0.0.1:${server.address.port}/model-host/").run { context ->
                assertThat(context).hasNotFailed()
                val service = context.getBean("local", DecisionService::class.java)
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                assertThat(service.name).isEqualTo("alias")
                assertThat(registry.decisions().defaultService()).isSameAs(service)
                assertThat(registry.decisions().byRole("support-triage")).isSameAs(service)
                assertThat(registry.classifications().defaultService()).isSameAs(service)
                assertThat(registry.classifications().byRole("ticket-routing")).isSameAs(service)
                assertThat(context.getBean(DecisionModelProvider::class.java).models().map { it.modelName }).containsExactly("alias")
                assertThat(calls.get()).isZero()
            }
            assertThat(calls.get()).isZero()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `empty Spring context executes a mixed typed request through production SystemOne HTTP`() {
        val calls = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val methods = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val mapper = JsonMapper.builder().build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = """{
            "model":"fixture-model",
            "answers":{
                "q001":{"type":"noul","noul":0.6},
                "q002":{"type":"choice","choice":"billing","probabilities":{"billing":0.6,"technical":0.4},"confidence":0.0},
                "q003":{"type":"score","score":0.75,"legend":{"0":"Low","1":"High"},"probabilities":{"0":0.25,"1":0.75},"confidence":0.2}
            },
            "usage":{"input_tokens":100,"output_tokens":0}
        }""".trimIndent().toByteArray()
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            paths += exchange.requestURI.toString()
            methods += exchange.requestMethod
            bodies += exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, fixture.size.toLong())
            exchange.responseBody.use { it.write(fixture) }
            exchange.close()
        }
        server.start()
        try {
            configured("http://127.0.0.1:${server.address.port}/model-host/").run { context ->
                assertThat(context).hasNotFailed()
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                assertThat(calls.get()).isZero()
                // tag::systemone-typed-execution[]
                val urgent = Questions.named("urgent").proposition("Is this urgent?").build()
                val route = Questions.named("route").choice("Which team?")
                    .option("billing", "Payments").option("technical", "Outages").build()
                val priority = Questions.named("priority").rating("Priority?")
                    .level("low", "Low").level("high", "High").build()
                val response = registry.decisions().byRole("support-triage")
                    .ask("A billing outage", DecisionSpec.of(urgent, route, priority))
                assertThat((response.answer(urgent) as PropositionResult.Answered).pTrue).isEqualTo(0.6)
                assertThat((response.answer(route) as ClassificationResult.Selected).categoryId).isEqualTo("billing")
                assertThat((response.answer(route) as ClassificationResult.Selected).confidence).isEqualTo(0.0)
                assertThat(response.choiceDistribution(route)!!.probabilities.map { it.probability }).containsExactly(0.6, 0.4)
                val rating = response.answer(priority) as RatingResult.Answered
                assertThat(rating.score!!.value).isEqualTo(0.75)
                assertThat(rating.distribution.map { it.probability }).containsExactly(0.25, 0.75)
                assertThat(rating.confidence).isEqualTo(0.2)
                // end::systemone-typed-execution[]
                assertThat(rating.provenance!!.modelName).isEqualTo("fixture-model")
                assertThat(response.requestFailure).isNull()
                assertThat(calls.get()).isEqualTo(1)
                assertThat(methods).containsExactly("POST")
                assertThat(paths).containsExactly("/model-host/v1/systemone")
                assertThat(mapper.readTree(bodies.single())).isEqualTo(mapper.readTree("""{
                    "model":"alias","state":"A billing outage",
                    "questions":{
                        "q001":{"type":"noul","instructions":"Is this urgent?"},
                        "q002":{"type":"choice","instructions":"Which team?","criteria":{"billing":"Payments","technical":"Outages"}},
                        "q003":{"type":"score","instructions":"Priority?","criteria":["Low","High"]}
                    }
                }"""))
                assertThat(registry.decisions().defaultService())
                    .isSameAs(registry.decisions().byRole("support-triage"))
                assertThat(registry.classifications().defaultService())
                    .isSameAs(registry.classifications().byRole("ticket-routing"))
            }
            assertThat(calls.get()).isEqualTo(1)
        } finally {
            server.stop(0)
        }
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
