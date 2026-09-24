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
package com.embabel.agent.autoconfigure.models.jev;

import com.embabel.agent.jev.api.JevClientOptions;
import com.embabel.agent.jev.api.JevClients;
import io.micrometer.observation.ObservationRegistry;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Supplies a native client independently of the Embabel platform and its model providers. */
@AutoConfiguration(
        beforeName = "org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration",
        afterName = "com.embabel.agent.autoconfigure.netty.NettyClientAutoConfiguration")
@ConditionalOnClass(TypeSafeClient.class)
@ConditionalOnProperty(prefix = "embabel.agent.platform.models.jev", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(TypeSafeClient.class)
@EnableConfigurationProperties(JevProperties.class)
public class JevAutoConfiguration {

    @Bean
    TypeSafeClient jevClient(JevProperties properties, Environment environment,
            @Qualifier("aiModelRestClientBuilder") ObjectProvider<RestClient.Builder> platformBuilders,
            ObjectProvider<RestClient.Builder> builders, ObjectProvider<ObservationRegistry> registries) {
        requireApiKey(properties, environment);
        var builder = platformBuilders.getIfUnique();
        if (builder == null) {
            builder = builders.getIfUnique();
        }
        var registry = registries.getIfUnique();
        if (builder != null && registry != null) {
            // Shared platform builders need the application's HTTP observations without mutation.
            builder = builder.clone().observationRegistry(registry);
        }
        var options = new JevClientOptions(properties.baseUri(), properties.model(),
                properties.connectTimeout(), properties.readTimeout(), properties.maxResponseBytes());
        return JevClients.create(options, () -> requireApiKey(properties, environment), builder,
                registry != null ? registry : ObservationRegistry.NOOP);
    }

    private static String requireApiKey(JevProperties properties, Environment environment) {
        var key = StringUtils.hasText(properties.apiKey()) ? properties.apiKey() : environment.getProperty("TYPESAFE_API_KEY");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("Jev API key is required when enabled");
        }
        return key;
    }
}
