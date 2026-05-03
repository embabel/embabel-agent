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
package com.embabel.agent.config.models.anthropic;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.tool.callback.AfterLlmCallContext;
import com.embabel.agent.api.tool.callback.AfterToolResultContext;
import com.embabel.agent.api.tool.callback.ToolLoopInspector;
import com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration;
import com.embabel.agent.core.Usage;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Conversation;
import com.embabel.chat.UserMessage;
import com.embabel.chat.support.InMemoryConversation;
import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static com.embabel.agent.config.models.anthropic.AnthropicCachingConfigKt.withAnthropicCaching;
import static com.embabel.agent.config.models.anthropic.AnthropicUsage.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Anthropic prompt caching functionality.
 * Tests different caching strategies: system prompt, tools, conversation history, and combinations.
 *
 * <p>Cost savings: Anthropic charges 10% for cache reads vs 100% for regular input
 * tokens, so this saves 90% on the cached portion of prompts!
 *
 * <p>Note: Anthropic requires a minimum of 1024 tokens for caching to activate.
 * Tests use long system prompts to exceed this threshold.
 *
 * <p>Usage convenience: Instead of casting to org.springframework.ai.anthropic.api.AnthropicApi.Usage,
 * use the extension methods:
 * <pre>
 * // Verbose way:
 * org.springframework.ai.anthropic.api.AnthropicApi.Usage native =
 *     (org.springframework.ai.anthropic.api.AnthropicApi.Usage) usage.getNativeUsage();
 * int tokens = native.cacheCreationInputTokens();
 *
 * // Convenient way (using static imports from AnthropicUsage):
 * int tokens = anthropicCacheCreationTokens(usage);
 * </pre>
 */
@SpringBootTest(
        properties = {
                "embabel.models.default-llm=claude-sonnet-4-5",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
                "spring.main.allow-bean-definition-overriding=true",

                // Debug logging for caching
                "logging.level.com.embabel.agent.config.models.anthropic.AnthropicOptionsConverter=DEBUG",
                "logging.level.org.springframework.ai.anthropic=DEBUG",
                "logging.level.org.springframework.ai.anthropic.api=DEBUG",
                "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE"
        }
)
@ActiveProfiles("caching")
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent",
                "com.embabel.example"
        }
)
@ComponentScan(
        basePackages = {
                "com.embabel.agent",
                "com.embabel.example"
        },
        excludeFilters = {
                @ComponentScan.Filter(
                        type = org.springframework.context.annotation.FilterType.REGEX,
                        pattern = ".*GlobalExceptionHandler.*"
                )
        }
)
@Import({AgentAnthropicAutoConfiguration.class})
class LlmAnthropicCachingIT {

    private static final Logger logger = LoggerFactory.getLogger(LlmAnthropicCachingIT.class);

    @Autowired
    private Ai ai;

    /**
     * Simple data class for planet information
     */
    static class Planet {
        private String name;
        private String type;

        public Planet() {
        }

        public Planet(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return "Planet{name='" + name + "', type='" + type + "'}";
        }
    }

    /**
     * Simple data class for number recall
     */
    static class NumberResponse {
        private Integer number;

        public NumberResponse() {
        }

        public NumberResponse(Integer number) {
            this.number = number;
        }

        public Integer getNumber() {
            return number;
        }

        public void setNumber(Integer number) {
            this.number = number;
        }

        @Override
        public String toString() {
            return "NumberResponse{number=" + number + "}";
        }
    }

    /**
     * Simple data class for city information
     */
    static class City {
        private String name;
        private String country;

        public City() {
        }

        public City(String name, String country) {
            this.name = name;
            this.country = country;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        @Override
        public String toString() {
            return "City{name='" + name + "', country='" + country + "'}";
        }
    }

    /**
     * Simple data class for temperature conversion result
     */
    static class TemperatureResult {
        private Integer celsius;
        private Integer fahrenheit;

        public TemperatureResult() {
        }

        public TemperatureResult(Integer fahrenheit, Integer celsius) {
            this.fahrenheit = fahrenheit;
            this.celsius = celsius;
        }

        public Integer getCelsius() {
            return celsius;
        }

        public void setCelsius(Integer celsius) {
            this.celsius = celsius;
        }

        public Integer getFahrenheit() {
            return fahrenheit;
        }

        public void setFahrenheit(Integer fahrenheit) {
            this.fahrenheit = fahrenheit;
        }

        @Override
        public String toString() {
            return "TemperatureResult{fahrenheit=" + fahrenheit + ", celsius=" + celsius + "}";
        }
    }

    /**
     * Comprehensive tool set for weather and temperature operations.
     * Multiple tools with detailed descriptions to reach 1024+ token minimum for caching.
     */
    static class WeatherTools {

        @LlmTool(description = "Convert temperature from Fahrenheit to Celsius scale. Fahrenheit is commonly used in the United States, while Celsius is the standard in most other countries and in scientific contexts.")
        public Integer convertFahrenheitToCelsius(Integer fahrenheit) {
            return (int) ((fahrenheit - 32) * 5.0 / 9.0);
        }

        @LlmTool(description = "Convert temperature from Celsius to Fahrenheit scale. This conversion is useful when working with American weather data or when communicating temperatures to audiences familiar with the Fahrenheit scale.")
        public Integer convertCelsiusToFahrenheit(Integer celsius) {
            return (int) ((celsius * 9.0 / 5.0) + 32);
        }

        @LlmTool(description = "Convert temperature from Celsius to Kelvin scale. Kelvin is the SI base unit of temperature and is used extensively in scientific and engineering applications, especially in thermodynamics and physics.")
        public Integer convertCelsiusToKelvin(Integer celsius) {
            return celsius + 273;
        }

        @LlmTool(description = "Convert temperature from Kelvin to Celsius scale. This is useful when converting scientific measurements back to the more commonly used Celsius scale for everyday communication.")
        public Integer convertKelvinToCelsius(Integer kelvin) {
            return kelvin - 273;
        }

        @LlmTool(description = "Calculate the heat index (apparent temperature) based on actual temperature in Fahrenheit and relative humidity percentage. The heat index is what the temperature feels like to the human body when relative humidity is combined with the air temperature.")
        public Integer calculateHeatIndex(Integer temperatureFahrenheit, Integer relativeHumidityPercent) {
            if (temperatureFahrenheit < 80) return temperatureFahrenheit;
            double t = temperatureFahrenheit;
            double rh = relativeHumidityPercent;
            double heatIndex = -42.379 + 2.04901523 * t + 10.14333127 * rh
                - 0.22475541 * t * rh - 0.00683783 * t * t
                - 0.05481717 * rh * rh + 0.00122874 * t * t * rh
                + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh;
            return (int) heatIndex;
        }

        @LlmTool(description = "Calculate the wind chill temperature (apparent temperature) based on actual temperature in Fahrenheit and wind speed in miles per hour. Wind chill describes how cold people and animals feel when outside, accounting for heat loss due to wind.")
        public Integer calculateWindChill(Integer temperatureFahrenheit, Integer windSpeedMph) {
            if (temperatureFahrenheit > 50 || windSpeedMph < 3) return temperatureFahrenheit;
            double t = temperatureFahrenheit;
            double v = windSpeedMph;
            double windChill = 35.74 + 0.6215 * t - 35.75 * Math.pow(v, 0.16) + 0.4275 * t * Math.pow(v, 0.16);
            return (int) windChill;
        }

        @LlmTool(description = "Calculate dew point temperature in Fahrenheit based on actual temperature and relative humidity. The dew point is the temperature to which air must be cooled to become saturated with water vapor, and is a key indicator of atmospheric moisture content.")
        public Integer calculateDewPoint(Integer temperatureFahrenheit, Integer relativeHumidityPercent) {
            double t = (temperatureFahrenheit - 32) * 5.0 / 9.0; // Convert to Celsius
            double rh = relativeHumidityPercent / 100.0;
            double a = 17.27;
            double b = 237.7;
            double alpha = ((a * t) / (b + t)) + Math.log(rh);
            double dewPointC = (b * alpha) / (a - alpha);
            return (int) ((dewPointC * 9.0 / 5.0) + 32); // Convert back to Fahrenheit
        }
    }

    @Test
    void testSystemPromptCaching() {
        logger.info("Testing system prompt caching");

        AnthropicCachingConfig cachingConfig = new AnthropicCachingConfig();
        cachingConfig.setSystemPrompt(true);

        LlmOptions options = LlmOptions.withDefaultLlm();
        options = withAnthropicCaching(options, cachingConfig);

        // Use a very long system prompt to exceed 1024 token minimum for caching
        // Add timestamp to ensure each test run creates a fresh cache (avoid pollution from previous runs)
        String longSystemPrompt = """
                [Test run: %s]
                You are a helpful assistant that specializes in astronomy and planetary science.
                """.formatted(System.currentTimeMillis()) + """
                You have extensive knowledge about our solar system, including all planets, moons, asteroids,
                comets, and other celestial bodies. You understand planetary formation, orbital mechanics,
                atmospheric composition, geological features, and the history of space exploration.
                You are familiar with the characteristics of each planet: Mercury with its extreme temperature
                variations and heavily cratered surface; Venus with its thick carbon dioxide atmosphere and
                runaway greenhouse effect; Earth with its unique biosphere and liquid water; Mars with its
                polar ice caps and evidence of ancient water; Jupiter with its Great Red Spot and numerous
                moons including the Galilean satellites; Saturn with its spectacular ring system and moon
                Titan; Uranus with its unusual axial tilt and icy composition; and Neptune with its supersonic
                winds and deep blue color. You understand the distinction between terrestrial and gas giant
                planets, the concept of dwarf planets like Pluto, and the Kuiper Belt objects. You are
                knowledgeable about exoplanets and planet detection methods like transit photometry and
                radial velocity measurements. You can discuss planetary atmospheres, magnetic fields,
                internal structures, and surface features. You understand crater formation, volcanism,
                tectonics, and weathering processes on different worlds. You are familiar with space missions
                like Voyager, Cassini, Mars rovers, New Horizons, and current missions exploring our solar
                system. You can explain concepts like orbital resonance, tidal locking, Lagrange points,
                and gravitational assists. You understand the history of astronomy from ancient civilizations
                to modern space telescopes. You can discuss the formation of the solar system from the
                solar nebula, planetesimal accretion, and the migration of planets. You are knowledgeable
                about moons and their characteristics, from Earth's Moon to the over 200 moons orbiting
                other planets. You understand ring systems, their composition, and dynamics. You can explain
                the differences between rocky, icy, and gas giant worlds. You are familiar with astrobiology
                and the search for habitable environments in our solar system and beyond. You can discuss
                the tools and methods astronomers use to study planets, including telescopes, spectroscopy,
                and spacecraft instruments. You understand comparative planetology and how studying other
                worlds helps us understand Earth. You can explain day length, seasons, axial tilts, and
                their effects on planetary climates. You are knowledgeable about impact events, both
                historical and potential future threats. You can discuss the evolution of planetary
                atmospheres over time and atmospheric escape processes. You understand stellar classification
                and the Sun's properties as a G-type main sequence star. You can explain nuclear fusion,
                solar activity, sunspots, solar flares, and coronal mass ejections. You are knowledgeable
                about the heliosphere and how the solar wind interacts with planetary magnetospheres.
                You understand the Oort Cloud and the origins of long-period comets. You can discuss
                near-Earth objects and asteroid families. You are familiar with meteorites and what they
                reveal about solar system formation. You understand the concept of the frost line and
                its role in determining planetary composition. You can explain planetary differentiation
                and core formation. You are knowledgeable about tidal forces and their effects like tidal
                heating on moons such as Io and Enceladus. You understand cryovolcanism and subsurface
                oceans on icy moons. You can discuss the magnetospheres of different planets and their
                auroral displays. You are familiar with the concept of planetary habitability and the
                habitable zone. You understand atmospheric dynamics, including weather patterns, wind
                systems, and climate zones on different planets. You can explain the greenhouse effect
                and its varying intensities across planetary bodies. You are knowledgeable about the
                history of solar system exploration, from early telescopic observations to modern robotic
                missions and future planned explorations.
                """;

        // Capture usage from each LLM call
        List<Usage> usageCapture = new ArrayList<>();
        ToolLoopInspector inspector = new ToolLoopInspector() {
            @Override
            public void afterLlmCall(AfterLlmCallContext context) {
                if (context.getUsage() != null) {
                    usageCapture.add(context.getUsage());
                }
            }
        };

        PromptRunner runner = ai.withLlm(options)
                .withSystemPrompt(longSystemPrompt)
                .withToolLoopInspectors(inspector);

        // First call - should create cache
        Planet planet1 = runner.createObject("What is the largest planet in our solar system?", Planet.class);
        assertNotNull(planet1);
        assertNotNull(planet1.getName());
        logger.info("First LLM response: {}", planet1);

        // Second call with same system prompt - should hit cache
        Planet planet2 = runner.createObject("What is the smallest planet in our solar system?", Planet.class);
        assertNotNull(planet2);
        assertNotNull(planet2.getName());
        logger.info("Second LLM response: {}", planet2);

        // Verify cache metrics using convenient extension methods
        assertEquals(2, usageCapture.size(), "Should have captured exactly 2 LLM calls");

        // First call should create cache
        Usage firstUsage = usageCapture.getFirst();
        assertNotNull(firstUsage, "First call should have usage");
        assertTrue(hasAnthropicCacheCreation(firstUsage), "First call should create cache");
        logger.info("First LLM call - {}", anthropicCacheSummary(firstUsage));

        // Second call should read from cache
        Usage secondUsage = usageCapture.getLast();
        assertNotNull(secondUsage, "Second call should have usage");
        assertTrue(hasAnthropicCacheRead(secondUsage), "Second call should read from cache");
        logger.info("Second LLM call - {}", anthropicCacheSummary(secondUsage));
    }

    @Test
    void testConversationHistoryCaching() {
        logger.info("Testing conversation history caching");

        AnthropicCachingConfig cachingConfig = new AnthropicCachingConfig();
        cachingConfig.setConversationHistory(true);

        LlmOptions options = LlmOptions.withDefaultLlm();
        options = withAnthropicCaching(options, cachingConfig);

        // Short system prompt - we're not testing system caching here
        String systemPrompt = "You are a helpful assistant.";

        // Capture usage from each LLM call
        List<Usage> usageCapture = new ArrayList<>();
        ToolLoopInspector inspector = new ToolLoopInspector() {
            @Override
            public void afterLlmCall(AfterLlmCallContext context) {
                if (context.getUsage() != null) {
                    usageCapture.add(context.getUsage());
                }
            }
        };

        PromptRunner runner = ai.withLlm(options)
                .withSystemPrompt(systemPrompt)
                .withToolLoopInspectors(inspector);

        // First message must be >4096 tokens to trigger caching for Claude Sonnet 4.5
        // Second call will concatenate this with additional text, allowing Anthropic to cache the repeated part
        // Add timestamp to ensure each test run creates a fresh cache (avoid pollution from previous runs)
        final String FIRST_MESSAGE = """
                [Test run: %s]
                Are you there? I need to tell you about a very important topic that requires detailed explanation.
                """.formatted(System.currentTimeMillis()) + """
                Let me explain the fundamentals of distributed systems and microservices architecture in comprehensive detail.

                Distributed systems are computing systems in which components located on networked computers
                communicate and coordinate their actions by passing messages. These systems are characterized
                by the lack of a global clock and the independent failure of components. In distributed systems,
                we face many significant challenges including network partitioning, latency, message loss, and
                the famous CAP theorem which states that it's impossible to simultaneously provide more than
                two out of three guarantees: Consistency, Availability, and Partition tolerance. This fundamental
                limitation shapes how we design and implement all distributed systems in practice.

                Microservices architecture is a comprehensive approach to developing a single application as a
                suite of small, independently deployable services, each running in its own process and communicating
                with lightweight mechanisms, often an HTTP resource API or message queues. These services are
                built around specific business capabilities and are independently deployable by fully automated
                deployment machinery. There is a bare minimum of centralized management of these services, which
                may be written in different programming languages and use different data storage technologies
                based on what's optimal for each specific service.

                The microservices approach provides numerous benefits including significantly improved modularity,
                allowing applications to be developed by focusing on small, well-defined services that can be
                developed, tested, and deployed independently by different teams. It enables each service to be
                deployed, upgraded, scaled, and restarted independent of other services in the application,
                greatly accelerating development cycles and enabling true continuous delivery practices. Teams
                can work autonomously on different services, choosing the best technology stack for their specific
                needs without being constrained by decisions made for other parts of the system.

                However, microservices also introduce significant complexity in several critical areas that must
                be carefully managed. Deployment becomes much more complex as you need to manage dozens or even
                hundreds of different services instead of a single monolithic application. Testing becomes
                substantially more challenging because you need to test not just individual services in isolation
                but also their complex interactions and integration points. Monitoring and debugging distributed
                systems requires specialized tools and sophisticated techniques that weren't necessary with
                monolithic applications. Network latency and unreliability become serious concerns that simply
                didn't exist when all code ran in a single process.

                Service discovery becomes absolutely crucial as services need to locate and communicate with each
                other dynamically in cloud environments where instances may come and go frequently. Popular tools
                like Consul, Eureka, etcd, and Kubernetes built-in service discovery mechanisms help solve this
                critical problem. Load balancing distributes incoming requests across multiple instances of
                services to ensure high availability, optimal resource utilization, and good performance under
                varying load conditions. Circuit breakers are essential patterns that prevent cascading failures
                by detecting when a downstream service is failing and temporarily stopping requests to that service,
                giving it time to recover and preventing the failure from spreading throughout the system.

                Distributed tracing is essential for debugging and monitoring complex request flows by tracking
                individual requests as they propagate through multiple services in the system. Sophisticated tools
                like Jaeger, Zipkin, AWS X-Ray, and the OpenTelemetry standard provide critical visibility into
                complex distributed transactions that would otherwise be nearly impossible to understand and debug.
                Container orchestration platforms like Kubernetes, Docker Swarm, and Amazon ECS help manage
                microservices at scale by automatically handling service deployment, horizontal scaling, load
                balancing, health checking, and self-healing capabilities.

                API gateways provide a unified single entry point for external clients and elegantly handle many
                cross-cutting concerns like authentication, authorization, rate limiting, request routing, protocol
                translation, request/response transformation, and API versioning. They act as a protective facade
                for the underlying microservices, simplifying client interactions and providing a valuable layer
                of abstraction that allows backend services to evolve independently without breaking client contracts.

                Event-driven architecture and message queues enable powerful asynchronous communication patterns
                between services, significantly improving overall system resilience and scalability. Rather than
                making synchronous HTTP calls that tightly couple services, services can publish domain events
                that other interested services subscribe to, creating much looser coupling. Technologies like
                Apache Kafka, RabbitMQ, AWS SQS, Google Pub/Sub, and Azure Service Bus facilitate this important
                communication pattern. This asynchronous approach allows services to continue operating effectively
                even when dependent services are temporarily unavailable or experiencing problems.

                The database per service pattern is a fundamental principle that ensures loose coupling by giving
                each microservice its own dedicated database, preventing problematic shared database dependencies
                that can create tight coupling and deployment bottlenecks. However, this pattern introduces
                significant new challenges in maintaining data consistency across services since you can no longer
                simply use traditional database transactions to ensure consistency across multiple tables that
                are now owned by completely different services.

                Saga patterns help effectively manage distributed transactions by breaking them down into a series
                of local transactions, each with a corresponding compensating transaction that can undo its effects
                if something goes wrong later in the process. This sophisticated approach allows for eventual
                consistency without requiring complex distributed transactions, which are notoriously difficult
                to implement correctly and scale effectively in distributed systems.

                CQRS, which stands for Command Query Responsibility Segregation, separates read and write operations
                to achieve better scalability, performance, and flexibility. Write operations, called commands,
                use one carefully optimized model focused on consistency, validation, and business rules, while
                read operations, called queries, use completely different models optimized specifically for
                querying, reporting, and display purposes. This important separation allows you to scale reads
                and writes completely independently and choose entirely different storage technologies optimized
                for each distinct use case.

                Security in microservices architectures requires a comprehensive defense-in-depth approach with
                authentication and authorization enforced at multiple layers throughout the system. Service-to-service
                authentication using mutual TLS certificates or JWT tokens ensures that only properly authorized
                services can communicate with each other. API gateways authenticate external clients before
                allowing access to internal services. Each individual service should independently validate
                permissions and authorization rather than blindly assuming that requests have already been
                properly authorized by upstream components.

                Observability is absolutely critical in microservices architectures where understanding system
                behavior is much more complex than in monolithic applications. The three fundamental pillars
                of observability are comprehensive logging, detailed metrics collection, and distributed tracing.
                Centralized logging solutions aggregate logs from all services into a single searchable repository,
                making it practical to debug issues that span multiple services. Metrics provide quantitative
                data about system performance, resource utilization, error rates, and overall health. Distributed
                tracing shows the complete path of requests as they flow through the entire system.

                Configuration management becomes substantially more complex when you have many services to manage.
                Externalized configuration using specialized tools like Spring Cloud Config, Consul, etcd, or
                Kubernetes ConfigMaps and Secrets allows you to modify configuration without redeploying services.
                Feature flags enable teams to gradually roll out new features to subsets of users and quickly
                roll back if problems are discovered, all without requiring any code deployment.

                Data consistency patterns like eventual consistency, saga patterns, event sourcing, and CQRS
                help teams effectively manage the inherent challenges of distributed data management. Event
                sourcing is a powerful pattern that stores all changes to application state as an immutable
                sequence of events rather than just storing the current state, providing a complete audit trail
                and enabling sophisticated temporal queries and projections.

                Containerization with Docker has become the de facto standard for packaging and deploying
                microservices, providing strong consistency guarantees across development, testing, and production
                environments. Containers are lightweight, start very quickly, include all dependencies and
                libraries needed to run the service, and provide good isolation between services running on
                the same host.

                DevOps practices and extensive automation are absolutely essential for successfully managing
                microservices at any significant scale. Continuous integration and continuous deployment pipelines
                automatically build, test, package, and deploy services whenever code changes are committed.
                Infrastructure as code tools like Terraform, CloudFormation, and Pulumi manage infrastructure
                resources declaratively and repeatably. This level of automation is not optional but absolutely
                necessary because manually managing dozens or hundreds of services is simply not feasible.

                Resilience patterns are crucial for building robust microservices systems. Retry logic with
                exponential backoff helps services gracefully handle transient failures. Timeouts prevent requests
                from hanging indefinitely when downstream services are slow or unresponsive. Bulkheads isolate
                resources to prevent failures in one area from affecting the entire system. Rate limiting protects
                services from being overwhelmed by too many requests.

                Service mesh technologies like Istio, Linkerd, and Consul Connect provide infrastructure-level
                solutions for service-to-service communication, security, observability, and traffic management.
                They handle concerns like mutual TLS, traffic routing, load balancing, circuit breaking, and
                distributed tracing without requiring changes to application code, moving these cross-cutting
                concerns into the infrastructure layer where they can be managed consistently across all services.

                API versioning strategies are important for maintaining backward compatibility while evolving
                services. Common approaches include URL versioning, header versioning, and content negotiation.
                Breaking changes require careful planning and gradual migration strategies to avoid disrupting
                existing clients. Semantic versioning helps communicate the nature of changes clearly.

                Database migration and schema evolution in microservices require careful coordination. Each service
                owns its schema, but changes must be backward compatible during deployment. Blue-green deployments
                and canary releases help reduce risk when rolling out changes. Database refactoring techniques
                like expand-contract patterns allow schema changes without downtime.

                Performance optimization in distributed systems involves many techniques. Caching at multiple
                levels reduces load on backend services and improves response times. Content delivery networks
                distribute static assets globally. Database query optimization and proper indexing are crucial.
                Connection pooling reduces overhead. Asynchronous processing moves slow operations off the
                critical request path. Compression reduces network transfer times.

                Monitoring and alerting systems must track key metrics across all services. Application performance
                monitoring tools like New Relic, Datadog, and AppDynamics provide deep insights. Infrastructure
                monitoring tracks resource utilization, network performance, and system health. Business metrics
                monitoring ensures technical systems support business objectives. Alert fatigue is a real problem
                that must be addressed by carefully tuning thresholds and reducing noise.

                Incident response and on-call procedures become more complex with microservices. Clear runbooks
                document troubleshooting procedures for common issues. Automated remediation can resolve many
                problems without human intervention. Blameless post-mortems help teams learn from incidents.
                Chaos engineering proactively identifies weaknesses by intentionally introducing failures.

                Testing strategies must cover multiple levels. Unit tests verify individual component behavior.
                Integration tests ensure services work together correctly. Contract testing validates service
                interfaces match expectations. End-to-end tests verify complete user workflows. Performance
                testing identifies bottlenecks and capacity limits. Chaos testing validates resilience to failures.

                Documentation is critical for team collaboration and knowledge sharing. API documentation using
                tools like Swagger/OpenAPI makes interfaces discoverable and understandable. Architecture decision
                records capture important design choices and their rationale. Runbooks guide operational tasks.
                Onboarding documentation helps new team members become productive quickly.

                Team organization and Conway's Law suggest that system architecture reflects organizational
                structure. Teams aligned with services can move faster and make decisions independently. Clear
                ownership and responsibility boundaries reduce coordination overhead. Cross-functional teams
                including developers, operations, and quality assurance work best for microservices.

                Cost management in cloud environments requires attention as microservices can proliferate. Right-sizing
                instances based on actual usage reduces waste. Auto-scaling adjusts capacity to match demand.
                Spot instances and reserved instances reduce costs. Resource quotas prevent runaway spending.
                Cost monitoring and chargebacks create accountability.

                Migration from monoliths to microservices should be gradual and strategic. The strangler fig pattern
                incrementally replaces monolith functionality with microservices. Start with the most valuable or
                problematic areas first. Maintain the monolith alongside microservices during transition. Extract
                services one at a time to reduce risk. Not everything needs to be a microservice - sometimes
                monoliths are appropriate.

                Domain-driven design provides valuable patterns for identifying service boundaries. Bounded contexts
                define clear boundaries between different parts of the domain. Aggregates group related entities
                that must maintain consistency. Domain events communicate changes between bounded contexts.
                Ubiquitous language ensures teams use consistent terminology.

                GraphQL and gRPC offer alternatives to REST for service communication. GraphQL allows clients
                to request exactly the data they need, reducing over-fetching and under-fetching. gRPC uses
                protocol buffers for efficient binary serialization and supports streaming. Each approach has
                trade-offs regarding complexity, tooling, and ecosystem support.

                Serverless architectures with functions-as-a-service take microservices to an extreme, eliminating
                server management entirely. AWS Lambda, Azure Functions, and Google Cloud Functions execute code
                in response to events. This approach works well for event-driven workloads and can reduce costs
                for variable traffic, but introduces constraints around execution time, state management, and
                cold starts.

                Data lakes and data warehouses help consolidate data from multiple microservices for analytics
                and reporting. Change data capture streams database changes to analytics systems. ETL pipelines
                transform and load data. Data governance ensures quality, security, and compliance. Business
                intelligence tools make data accessible to decision makers.

                Regulatory compliance and data privacy requirements like GDPR and HIPAA add complexity to
                distributed systems. Data residency requirements may restrict where data can be stored and
                processed. Audit logging tracks all access to sensitive data. Encryption protects data in
                transit and at rest. Data retention policies determine how long data must be kept.

                Disaster recovery and business continuity planning are essential for critical systems. Regular
                backups ensure data can be restored after failures. Geographic redundancy protects against
                regional outages. Recovery time objectives and recovery point objectives define acceptable
                downtime and data loss. Regular disaster recovery drills validate procedures work as expected.

                The future of microservices continues to evolve with new patterns and technologies. Micro
                frontends apply microservices principles to user interfaces. Edge computing brings processing
                closer to users and devices. WebAssembly enables new deployment options. AI and machine learning
                create new types of services and capabilities. The fundamentals of distributed systems remain
                constant even as specific technologies change.
                """;


        // First call - send the first message
        String response1 = runner.createObject(FIRST_MESSAGE, String.class);
        assertNotNull(response1);
        logger.info("First long message response: {}", response1);

        // Second call - manually construct conversation history with previous exchange
        // This simulates: [USER: FIRST_MESSAGE, ASSISTANT: response1, USER: "What are you doing?"]
        // Anthropic will cache the conversation history prefix [USER: FIRST_MESSAGE, ASSISTANT: response1]
        String response2 = runner.createObject(
                List.of(
                        new com.embabel.chat.UserMessage(FIRST_MESSAGE),
                        new com.embabel.chat.AssistantMessage(response1),
                        new com.embabel.chat.UserMessage("What are you doing?")
                ),
                String.class
        );
        assertNotNull(response2);
        logger.info("Second long message response: {}", response2);

        // Verify cache metrics using convenient extension methods
        assertEquals(2, usageCapture.size(), "Should have captured exactly 2 LLM calls");

        // First call - no cache yet
        Usage firstUsage = usageCapture.getFirst();
        assertNotNull(firstUsage, "First call should have usage");
        logger.info("First LLM call - {}", anthropicCacheSummary(firstUsage));

        // Second call - should cache the conversation history
        Usage secondUsage = usageCapture.getLast();
        assertNotNull(secondUsage, "Second call should have usage");
        assertTrue(hasAnthropicCacheRead(secondUsage), "Second call should read from cache");
        logger.info("Second LLM call - {}", anthropicCacheSummary(secondUsage));
    }

    @Test
    void testConversationHistoryCachingWithRespondApi() {
        logger.info("Testing conversation history caching with respond API and Conversation object");

        AnthropicCachingConfig cachingConfig = new AnthropicCachingConfig();
        cachingConfig.setConversationHistory(true);

        LlmOptions options = LlmOptions.withDefaultLlm();
        options = withAnthropicCaching(options, cachingConfig);

        // Short system prompt - we're not testing system caching here
        String systemPrompt = "You are a helpful assistant.";

        // Capture usage from each LLM call
        List<Usage> usageCapture = new ArrayList<>();
        ToolLoopInspector inspector = new ToolLoopInspector() {
            @Override
            public void afterLlmCall(AfterLlmCallContext context) {
                if (context.getUsage() != null) {
                    usageCapture.add(context.getUsage());
                }
            }
        };

        PromptRunner runner = ai.withLlm(options)
                .withSystemPrompt(systemPrompt)
                .withToolLoopInspectors(inspector);

        // Create a conversation object with timestamp to avoid cache pollution
        Conversation conversation = new InMemoryConversation();

        // First message must be >4096 tokens to trigger caching for Claude Sonnet 4.5
        // Add timestamp to ensure each test run creates a fresh cache (avoid pollution from previous runs)
        final String FIRST_MESSAGE = """
                [Test run: %s]
                Please tell me about microservices architecture in detail. I need a comprehensive explanation.
                Let me explain what I'm looking for. Microservices architecture is a comprehensive approach to
                developing applications as a suite of small, independently deployable services. Each microservice
                runs in its own process and communicates through lightweight mechanisms such as HTTP APIs or
                message queues. These services are built around specific business capabilities and can be deployed
                independently using automated deployment infrastructure. The architecture provides numerous benefits
                including improved modularity, independent deployment and scaling, technology diversity, and fault
                isolation. However, it also introduces complexity in areas such as distributed data management,
                inter-service communication, deployment orchestration, and monitoring. Teams need to carefully
                consider service boundaries, data consistency patterns, API versioning strategies, and resilience
                patterns like circuit breakers and retries. Service discovery mechanisms help services locate each
                other dynamically, while API gateways provide a single entry point for clients. Container
                technologies like Docker combined with orchestration platforms like Kubernetes have become essential
                for managing microservices at scale. Observability through distributed tracing, centralized logging,
                and metrics collection is crucial for understanding system behavior. The transition from monolithic
                to microservices architecture requires careful planning and often follows patterns like the
                strangler fig pattern. Security considerations include service-to-service authentication, API
                security, and secrets management. Data management strategies must address challenges of distributed
                transactions and eventual consistency. Testing becomes more complex, requiring strategies for unit
                testing, integration testing, contract testing, and end-to-end testing. Development teams typically
                organize around services, with each team owning one or more services. Continuous integration and
                continuous deployment pipelines automate the build, test, and deployment processes. Configuration
                management and feature flags enable teams to control service behavior without code changes. The
                architecture supports rapid development and deployment cycles, allowing organizations to respond
                quickly to changing requirements. However, the operational complexity increases significantly
                compared to monolithic applications. Teams need expertise in distributed systems, DevOps practices,
                and cloud platforms. The decision to adopt microservices should consider factors like team size,
                organizational structure, and application requirements. Many successful implementations start with
                a monolith and gradually extract services as the application grows. The key is finding the right
                balance between the benefits of microservices and the complexity they introduce.
                """.formatted(System.currentTimeMillis());

        // Add first message to conversation
        conversation.addMessage(new UserMessage(FIRST_MESSAGE));

        // First call using respond() API with Conversation object
        AssistantMessage response1 = runner.respond(conversation.getMessages());
        assertNotNull(response1);
        assertNotNull(response1.getContent());
        logger.info("First response: {}", response1.getContent().substring(0, Math.min(100, response1.getContent().length())));

        // Add assistant response to conversation
        conversation.addMessage(response1);

        // Add second user message
        conversation.addMessage(new UserMessage("What are the main challenges?"));

        // Second call using respond() API - conversation now long enough to create cache
        AssistantMessage response2 = runner.respond(conversation.getMessages());
        assertNotNull(response2);
        assertNotNull(response2.getContent());
        logger.info("Second response: {}", response2.getContent().substring(0, Math.min(100, response2.getContent().length())));

        // Add second assistant response to conversation
        conversation.addMessage(response2);

        // Add third user message
        conversation.addMessage(new UserMessage("Can you summarize the top 3 challenges?"));

        // Third call using respond() API - should read from cache created by second call
        AssistantMessage response3 = runner.respond(conversation.getMessages());
        assertNotNull(response3);
        assertNotNull(response3.getContent());
        logger.info("Third response: {}", response3.getContent().substring(0, Math.min(100, response3.getContent().length())));

        // Verify cache metrics
        assertEquals(3, usageCapture.size(), "Should have captured exactly 3 LLM calls");

        // First call - conversation too short to cache
        Usage firstUsage = usageCapture.get(0);
        assertNotNull(firstUsage, "First call should have usage");
        logger.info("First LLM call - {}", anthropicCacheSummary(firstUsage));

        // Second call - conversation now long enough, creates cache
        Usage secondUsage = usageCapture.get(1);
        assertNotNull(secondUsage, "Second call should have usage");
        assertTrue(hasAnthropicCacheCreation(secondUsage), "Second call should create cache");
        logger.info("Second LLM call - {}", anthropicCacheSummary(secondUsage));

        // Third call - reads from cache created by second call
        Usage thirdUsage = usageCapture.get(2);
        assertNotNull(thirdUsage, "Third call should have usage");
        assertTrue(hasAnthropicCacheRead(thirdUsage), "Third call should read from cache");
        logger.info("Third LLM call - {}", anthropicCacheSummary(thirdUsage));
    }

    @Test
    void testMessageSizeControl() {
        logger.info("Testing message size control (min content length)");

        // PART 1: Test that short content is NOT cached when below minimum
        logger.info("Part 1: Testing short system prompt (below minimum)");

        AnthropicCachingConfig shortConfig = new AnthropicCachingConfig();
        shortConfig.setSystemPrompt(true);
        shortConfig.messageTypeMinContentLength(com.embabel.chat.MessageRole.SYSTEM, 500);

        LlmOptions shortOptions = LlmOptions.withDefaultLlm();
        shortOptions = withAnthropicCaching(shortOptions, shortConfig);

        List<Usage> shortUsageCapture = new ArrayList<>();
        ToolLoopInspector shortInspector = new ToolLoopInspector() {
            @Override
            public void afterLlmCall(AfterLlmCallContext context) {
                if (context.getUsage() != null) {
                    shortUsageCapture.add(context.getUsage());
                }
            }
        };

        // Short system prompt (< 500 chars) - should NOT be cached
        String shortSystemPrompt = "You are a helpful assistant specialized in geography.";

        PromptRunner shortRunner = ai.withLlm(shortOptions)
                .withSystemPrompt(shortSystemPrompt)
                .withToolLoopInspectors(shortInspector);

        City city1 = shortRunner.createObject("What is the capital of France?", City.class);
        assertNotNull(city1);
        logger.info("Short prompt first response: {}", city1);

        City city2 = shortRunner.createObject("What about Germany?", City.class);
        assertNotNull(city2);
        logger.info("Short prompt second response: {}", city2);

        // Verify no caching occurred
        assertEquals(2, shortUsageCapture.size(), "Should have captured exactly 2 LLM calls");

        Usage shortFirstUsage = shortUsageCapture.getFirst();
        assertFalse(hasAnthropicCacheCreation(shortFirstUsage), "Short system prompt should not create cache");
        logger.info("Short prompt first call - {}", anthropicCacheSummary(shortFirstUsage));

        Usage shortSecondUsage = shortUsageCapture.getLast();
        assertFalse(hasAnthropicCacheRead(shortSecondUsage), "Should not read cache when min length not met");
        logger.info("Short prompt second call - {}", anthropicCacheSummary(shortSecondUsage));

        // PART 2: Test that long content IS cached when above Anthropic's 4096 token minimum
        logger.info("Part 2: Testing long system prompt (above Anthropic's 4096 token minimum)");

        AnthropicCachingConfig longConfig = new AnthropicCachingConfig();
        longConfig.setSystemPrompt(true);
        // No messageTypeMinContentLength - we're testing Anthropic's built-in minimum, not our filter

        LlmOptions longOptions = LlmOptions.withDefaultLlm();
        longOptions = withAnthropicCaching(longOptions, longConfig);

        List<Usage> longUsageCapture = new ArrayList<>();
        ToolLoopInspector longInspector = new ToolLoopInspector() {
            @Override
            public void afterLlmCall(AfterLlmCallContext context) {
                if (context.getUsage() != null) {
                    longUsageCapture.add(context.getUsage());
                }
            }
        };

        // Long system prompt (must exceed both our 500 char minimum AND Anthropic's 4096 token minimum)
        // Use a different prompt from testSystemPromptCaching to avoid cache collision
        // This needs to be ~4-5x longer than the short geography prompt to exceed 4096 tokens
        // Add timestamp to ensure each test run creates a fresh cache (avoid pollution from previous runs)
        String longSystemPrompt = """
                [Test run: %s]
                You are a helpful assistant that specializes in world geography and cultural studies.
                """.formatted(System.currentTimeMillis()) + """
                You have comprehensive knowledge about all countries, capitals, major cities, and their
                geographical features. You understand political boundaries, territorial divisions, and
                administrative regions across the globe. You are familiar with physical geography including
                mountain ranges, rivers, lakes, oceans, deserts, and other natural formations. You know
                about the Himalayas with Mount Everest, the Andes stretching along South America, the
                Rocky Mountains of North America, and the Alps of Europe. You understand river systems
                like the Amazon, Nile, Yangtze, Mississippi, and Danube. You are knowledgeable about
                climate zones, biomes, and ecosystems ranging from tropical rainforests to arctic tundra.
                You can discuss population distributions, urbanization patterns, and demographic trends.
                You understand economic geography including natural resources, agriculture, industry, and
                trade routes. You are familiar with historical geography and how borders have changed over
                time through wars, treaties, and political movements. You know about cultural regions,
                languages, religions, and ethnic groups around the world. You can explain geopolitical
                relationships, international organizations, and regional alliances. You understand time
                zones, the International Date Line, and coordinate systems. You are knowledgeable about
                cartography, map projections, and GIS technologies. You can discuss environmental issues
                like deforestation, desertification, and climate change impacts on different regions.
                You understand plate tectonics, volcanic activity, and seismic zones. You are familiar
                with island nations, archipelagos, peninsulas, and coastal features. You know about
                landlocked countries, enclaves, and exclaves. You can explain elevation, topography, and
                terrain characteristics. You understand water resources, watersheds, and hydrological
                cycles. You are knowledgeable about natural disasters including hurricanes, typhoons,
                floods, droughts, and their geographic distribution. You can discuss agricultural zones,
                growing seasons, and food production regions. You understand mineral deposits, oil and
                gas reserves, and their global distribution. You are familiar with transportation networks
                including highways, railways, airports, and shipping lanes. You know about UNESCO World
                Heritage Sites and their cultural or natural significance. You can explain biodiversity
                hotspots and conservation areas. You understand polar regions, ice sheets, and glaciers.
                You are knowledgeable about coral reefs, wetlands, and marine ecosystems. You can discuss
                urban planning, megacities, and metropolitan areas. You understand migration patterns,
                refugee movements, and population shifts. You are familiar with indigenous territories
                and traditional lands. You know about national parks, protected areas, and wilderness
                regions. You can explain monsoons, trade winds, and atmospheric circulation patterns.
                You understand soil types, fertility, and land use patterns. You are knowledgeable about
                renewable energy resources including solar, wind, hydro, and geothermal potential across
                regions. You can discuss water scarcity, access to clean water, and sanitation issues.
                You understand forest coverage, logging industries, and reforestation efforts. You are
                familiar with fishing zones, marine boundaries, and ocean resources. You know about
                permafrost, frozen ground, and cold region characteristics. You can explain the geographic
                distribution of major religions including Christianity, Islam, Hinduism, Buddhism, and
                Judaism, as well as traditional and indigenous belief systems. You understand linguistic
                diversity with over 7000 languages spoken worldwide, language families like Indo-European,
                Sino-Tibetan, and Niger-Congo, and the geographic distribution of major languages. You
                are knowledgeable about economic systems and development levels across different regions,
                including developed economies in North America, Europe, and parts of Asia, emerging
                markets in Latin America and Southeast Asia, and developing economies in parts of Africa
                and South Asia. You can discuss trade agreements like NAFTA, the European Union, ASEAN,
                and Mercosur, and their impact on regional integration. You understand colonial history
                and its lasting impact on modern political boundaries, languages, and cultural practices
                in Africa, Asia, and the Americas. You are familiar with independence movements and
                decolonization processes throughout the 20th century. You can explain the formation of
                new nations and border disputes that continue to shape geopolitics today. You understand
                the geography of conflict zones, disputed territories, and areas of geopolitical tension
                including Kashmir, the South China Sea, the Arctic, and various border regions. You are
                knowledgeable about international waterways and strategic passages like the Suez Canal,
                Panama Canal, Strait of Hormuz, Strait of Malacca, and Bosphorus. You can discuss the
                importance of these chokepoints for global trade and energy security. You understand
                exclusive economic zones, territorial waters, and the Law of the Sea. You are familiar
                with landlocked nations and their unique economic and strategic challenges, including
                countries like Mongolia, Nepal, Bolivia, Switzerland, and various Central Asian and
                African nations. You can explain how geographic factors like climate, topography, and
                natural resources have shaped human settlement patterns throughout history. You understand
                the concept of geographic determinism and environmental possibilism in human development.
                You are knowledgeable about urbanization trends with over half the world's population
                now living in cities, the growth of megacities with populations exceeding 10 million,
                and the challenges of urban infrastructure, housing, transportation, and services. You
                can discuss urban sprawl, suburbanization, gentrification, and smart city initiatives.
                You understand the geography of global health including disease distribution patterns,
                endemic and epidemic zones, access to healthcare across different regions, and how
                environmental factors influence disease transmission. You are familiar with food security
                and agricultural geography including grain-producing regions, the Green Revolution's
                impact, irrigation systems, and the challenges of feeding a growing global population.
                You can explain the geography of technology and innovation hubs including Silicon Valley,
                Bangalore, Shenzhen, Tel Aviv, and emerging tech centers worldwide. You understand digital
                divides and variations in internet access and technological infrastructure across regions.
                You are knowledgeable about tourism geography including major tourist destinations,
                cultural heritage sites, natural wonders, and the economic impact of tourism on different
                regions. You can discuss sustainable tourism, overtourism challenges, and ecotourism
                initiatives. You understand the geography of education including literacy rates, access
                to schooling, prestigious universities and their global influence, and international
                student mobility patterns. You are familiar with cultural geography including traditional
                clothing, cuisine, music, dance, festivals, and customs that vary across regions. You
                can explain how geography influences cultural practices, from building styles adapted to
                climate to food traditions based on locally available ingredients. You understand the
                geography of sports including the global distribution of football, cricket, baseball,
                basketball, and other popular sports, as well as traditional regional sports and games.
                You are knowledgeable about the Olympic movement and how it brings together nations from
                across the globe. You can discuss the geography of media and entertainment including
                Hollywood, Bollywood, Nollywood, and other film industries, as well as the global spread
                of television, music, and digital entertainment. You understand time zones and how they
                affect global communication, business operations, and coordination across borders. You
                are familiar with the International Date Line and how it works. You can explain daylight
                saving time practices in different countries and regions.
                """;

        PromptRunner longRunner = ai.withLlm(longOptions)
                .withSystemPrompt(longSystemPrompt)
                .withToolLoopInspectors(longInspector);

        City city3 = longRunner.createObject("What is the capital of Japan?", City.class);
        assertNotNull(city3);
        logger.info("Long prompt first response: {}", city3);

        City city4 = longRunner.createObject("What about South Korea?", City.class);
        assertNotNull(city4);
        logger.info("Long prompt second response: {}", city4);

        // Verify caching is active (content exceeds Anthropic's 4096 token minimum)
        assertEquals(2, longUsageCapture.size(), "Should have captured exactly 2 LLM calls");

        Usage longFirstUsage = longUsageCapture.getFirst();
        assertTrue(hasAnthropicCacheCreation(longFirstUsage), "Long system prompt should create cache");
        logger.info("Long prompt first call - {}", anthropicCacheSummary(longFirstUsage));

        Usage longSecondUsage = longUsageCapture.getLast();
        // Second call should have cache activity (either creation OR read)
        // Note: Due to how Spring AI applies cache directives with SYSTEM_ONLY strategy,
        // both calls may create cache rather than second reading from first
        assertTrue(hasAnthropicCacheCreation(longSecondUsage) || hasAnthropicCacheRead(longSecondUsage),
                "Second call should have cache activity (creation or read)");
        logger.info("Long prompt second call - {}", anthropicCacheSummary(longSecondUsage));
    }

    @Test
    void testToolCaching() {
        logger.info("Testing tool caching");

        AnthropicCachingConfig cachingConfig = new AnthropicCachingConfig();
        cachingConfig.setSystemPrompt(true);  // Need system prompt for tokens
        cachingConfig.setTools(true);          // Enable tool caching

        LlmOptions options = LlmOptions.withDefaultLlm();
        options = withAnthropicCaching(options, cachingConfig);

        // Use a long system prompt to exceed 1024 token minimum when combined with tool definitions
        // Add timestamp to ensure each test run creates a fresh cache (avoid pollution from previous runs)
        String longSystemPrompt = """
                [Test run: %s]
                You are a helpful assistant specializing in temperature conversions and thermodynamics.
                """.formatted(System.currentTimeMillis()) + """
                You have deep knowledge of temperature scales including Fahrenheit, Celsius, Kelvin, and Rankine.
                You understand the historical development of temperature measurement, from early thermoscopes
                to modern precision thermometers. You can explain the physical properties of temperature,
                including thermal energy, heat transfer mechanisms (conduction, convection, radiation), and
                the laws of thermodynamics. You are familiar with various applications of temperature measurement
                in meteorology, cooking, industrial processes, scientific research, and everyday life.
                You understand the relationships between temperature scales and can perform conversions accurately.
                You know that water freezes at 32°F (0°C) and boils at 212°F (100°C) at standard atmospheric
                pressure. You can discuss absolute zero (-273.15°C or -459.67°F or 0K) and its significance
                in physics. You understand thermal expansion, specific heat capacity, latent heat, and phase
                transitions. You are knowledgeable about temperature extremes in nature, from the coldest
                temperatures in space to the hottest temperatures in stars and during nuclear reactions.
                You can explain how thermometers work, including mercury thermometers, digital thermometers,
                thermocouples, and infrared thermometers. You understand the importance of temperature
                control in various industries including food safety, manufacturing, and healthcare.
                You are familiar with climate science and how temperature affects weather patterns and
                climate zones. You can discuss the greenhouse effect, global temperature trends, and
                temperature anomalies. You understand the role of temperature in chemical reactions
                and reaction rates. You are knowledgeable about thermal comfort and how temperature
                affects human physiology. You can explain fever temperatures and hypothermia thresholds.
                You understand temperature gradients, thermal equilibrium, and heat engines.
                """;

        // Capture usage from each LLM call and track tool executions
        List<Usage> usageCapture = new ArrayList<>();
        ToolLoopInspector inspector = new ToolLoopInspector() {
            @Override
            public void afterLlmCall(AfterLlmCallContext context) {
                if (context.getUsage() != null) {
                    usageCapture.add(context.getUsage());
                    logger.debug("LLM call {} in iteration {}", usageCapture.size(), context.getIteration());
                }
            }

            @Override
            public void afterToolResult(AfterToolResultContext context) {
                logger.debug("Tool executed: {} in iteration {}",
                    context.getToolCall().getName(), context.getIteration());
            }
        };

        PromptRunner runner = ai.withLlm(options)
                .withSystemPrompt(longSystemPrompt)
                .withToolObject(new WeatherTools())
                .withToolLoopInspectors(inspector);

        // First call - should create cache with tool definition
        TemperatureResult result1 = runner.createObject(
                "Convert 32 degrees Fahrenheit to Celsius using the provided tool",
                TemperatureResult.class
        );
        assertNotNull(result1);
        assertEquals(0, result1.getCelsius());
        logger.info("First Tool response: {}", result1);

        // Second call - should hit cache with same tool definition
        TemperatureResult result2 = runner.createObject(
                "Convert 212 degrees Fahrenheit to Celsius using the provided tool",
                TemperatureResult.class
        );
        assertNotNull(result2);
        assertEquals(100, result2.getCelsius());
        logger.info("Second Tool response: {}", result2);

        // Verify cache metrics using convenient extension methods
        // Tool loops make multiple LLM calls: initial call + call after tool result
        // We want the first call of each createObject where cache breakpoints apply
        assertTrue(usageCapture.size() >= 2, "Should have captured at least 2 LLM calls");
        logger.info("Captured {} LLM calls total", usageCapture.size());

        // Log all captured usage for debugging
        for (int i = 0; i < usageCapture.size(); i++) {
            logger.info("LLM call {} - {}", i, anthropicCacheSummary(usageCapture.get(i)));
        }

        // First createObject's first LLM call should create cache
        Usage firstUsage = usageCapture.get(0);
        assertNotNull(firstUsage, "First call should have usage");
        assertTrue(hasAnthropicCacheCreation(firstUsage), "First call should create cache");
        logger.info("First createObject initial call - {}", anthropicCacheSummary(firstUsage));

        // Find the first call of the second createObject (should read from cache)
        // In tool loops, each createObject typically makes 2 calls, so second createObject starts at index 2
        int secondCreateObjectFirstCallIndex = usageCapture.size() >= 4 ? 2 : 1;
        Usage secondUsage = usageCapture.get(secondCreateObjectFirstCallIndex);
        assertNotNull(secondUsage, "Second createObject first call should have usage");
        assertTrue(hasAnthropicCacheRead(secondUsage), "Second createObject first call should read from cache");
        logger.info("Second createObject initial call - {}", anthropicCacheSummary(secondUsage));
    }
}
