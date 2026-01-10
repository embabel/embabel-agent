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
package com.embabel.agent.autoconfigure.netty;

import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;


@ConfigurationProperties(prefix = "embabel.agent.platform.http-client")
record NettyClientFactoryProperties(
        Duration connectTimeout,
        Duration readTimeout
) {
    public NettyClientFactoryProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(25);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofMinutes(1);
    }
}

@AutoConfiguration
@EnableConfigurationProperties(NettyClientFactoryProperties.class)
@AutoConfigureBefore(name = "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration")
public class NettyClientAutoConfiguration {

    @Bean("aiModelHttpRequestFactory")
    @ConditionalOnProperty(value = "embabel.agent.platform.http-client.use-reactor-netty", havingValue = "true", matchIfMissing = true)
    ClientHttpRequestFactory reactorClientHttpRequestFactory(NettyClientFactoryProperties httpClientProperties) {
        var httpClient = HttpClient.create().responseTimeout(httpClientProperties
                        .readTimeout())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) httpClientProperties
                        .connectTimeout()
                        .toMillis());

        return new ReactorClientHttpRequestFactory(httpClient);
    }

}
