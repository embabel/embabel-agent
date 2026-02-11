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
package com.embabel.agent.autoconfigure.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Observation filter that enriches HTTP server observations with request/response details.
 *
 * <p>Extracts headers, query parameters, request body, and response body from
 * {@link ServerRequestObservationContext} and adds them as high-cardinality key values.
 * Sensitive headers (e.g. Authorization) are masked.
 *
 * <p>Body extraction requires {@link HttpBodyCachingFilter} to be active so that
 * {@link ContentCachingRequestWrapper} and {@link ContentCachingResponseWrapper} are available.
 *
 * <p>Only active when {@code embabel.observability.trace-http-details=true}.
 *
 * @since 0.3.4
 */
public class HttpRequestObservationFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestObservationFilter.class);

    private static final Set<String> TRACKED_HEADERS = Set.of(
            "content-type", "accept", "authorization", "user-agent"
    );
    private static final Set<String> MASKED_HEADERS = Set.of("authorization");

    private final int maxAttributeLength;

    public HttpRequestObservationFilter(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    @Override
    @NotNull
    public Observation.Context map(@NotNull Observation.Context context) {
        if (!(context instanceof ServerRequestObservationContext serverContext)) {
            return context;
        }
        try {
            addHeaders(serverContext);
            addParams(serverContext);
            addRequestBody(serverContext);
            addResponseBody(serverContext);
        } catch (Exception e) {
            log.debug("Failed to extract HTTP details from observation context", e);
        }
        return context;
    }

    private void addHeaders(ServerRequestObservationContext context) {
        var request = context.getCarrier();
        var joiner = new StringJoiner("; ");
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            var name = names.nextElement();
            if (!TRACKED_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            var value = MASKED_HEADERS.contains(name.toLowerCase())
                    ? "***"
                    : request.getHeader(name);
            joiner.add(name + ": " + value);
        }
        if (joiner.length() > 0) {
            context.addHighCardinalityKeyValue(KeyValue.of("http.request.headers", joiner.toString()));
        }
    }

    private void addParams(ServerRequestObservationContext context) {
        var request = context.getCarrier();
        var paramMap = request.getParameterMap();
        if (paramMap.isEmpty()) {
            return;
        }
        var joiner = new StringJoiner("&");
        paramMap.forEach((key, values) -> {
            for (var value : values) {
                joiner.add(key + "=" + value);
            }
        });
        context.addHighCardinalityKeyValue(KeyValue.of("http.request.params", joiner.toString()));
    }

    private void addRequestBody(ServerRequestObservationContext context) {
        var request = context.getCarrier();
        if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
            return;
        }
        var body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of("http.request.body", truncate(body)));
        }
    }

    private void addResponseBody(ServerRequestObservationContext context) {
        var response = context.getResponse();
        if (!(response instanceof ContentCachingResponseWrapper wrapper)) {
            return;
        }
        var body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of("http.response.body", truncate(body)));
        }
    }

    private String truncate(String value) {
        return value.length() > maxAttributeLength
                ? value.substring(0, maxAttributeLength) + "..."
                : value;
    }
}
