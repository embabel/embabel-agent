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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class NettyClientAutoConfigurationTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void factoryFollowsRedirects() {
        server.createContext("/target", exchange -> {
            byte[] body = "final destination".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/target");
            exchange.sendResponseHeaders(302, -1);
        });
        server.start();

        var config = new NettyClientAutoConfiguration();
        var props = new NettyClientFactoryProperties(Duration.ofSeconds(5), Duration.ofSeconds(5));

        String result = config.reactorRestClientBuilder(props)
                .build()
                .get()
                .uri(URI.create("http://localhost:" + port + "/redirect"))
                .retrieve()
                .body(String.class);

        assertEquals("final destination", result);
    }

    @Test
    void builderIsNettyBacked() throws NoSuchFieldException, IllegalAccessException {
        var config = new NettyClientAutoConfiguration();
        var props = new NettyClientFactoryProperties(null, null);
        var builder = config.reactorRestClientBuilder(props);

        assertInstanceOf(RestClient.Builder.class, builder);
        RestClient restClient = builder.build();
        Field requestFactoryField = restClient.getClass().getDeclaredField("clientRequestFactory");
        requestFactoryField.setAccessible(true);
        Object factory = requestFactoryField.get(restClient);
        assertInstanceOf(
                org.springframework.http.client.ReactorClientHttpRequestFactory.class,
                factory
        );
    }

    @Test
    void webClientBuilderIsNettyBacked() throws NoSuchFieldException, IllegalAccessException {
        var config = new NettyClientAutoConfiguration();
        var props = new NettyClientFactoryProperties(null, null);
        var builder = config.reactorWebClientBuilder(props);

        assertInstanceOf(WebClient.Builder.class, builder);
        WebClient webClient = builder.build();
        Field exchangeFunctionField = webClient.getClass().getDeclaredField("exchangeFunction");
        exchangeFunctionField.setAccessible(true);
        Object exchangeFunction = exchangeFunctionField.get(webClient);
        Field connectorField = exchangeFunction.getClass().getDeclaredField("connector");
        connectorField.setAccessible(true);
        Object connector = connectorField.get(exchangeFunction);
        assertInstanceOf(ReactorClientHttpConnector.class, connector);
    }
}