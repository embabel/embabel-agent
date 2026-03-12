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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
        ClientHttpRequestFactory factory = config.reactorClientHttpRequestFactory(props);

        String result = RestClient.builder()
                .requestFactory(factory)
                .build()
                .get()
                .uri(URI.create("http://localhost:" + port + "/redirect"))
                .retrieve()
                .body(String.class);

        assertEquals("final destination", result);
    }

    @Test
    void factoryCreatesReactorClientHttpRequestFactory() {
        var config = new NettyClientAutoConfiguration();
        var props = new NettyClientFactoryProperties(null, null);
        ClientHttpRequestFactory factory = config.reactorClientHttpRequestFactory(props);

        assertInstanceOf(
                org.springframework.http.client.ReactorClientHttpRequestFactory.class,
                factory
        );
    }
}