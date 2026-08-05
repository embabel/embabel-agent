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

import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

/**
 * Applies one aspect of the tracer provider configuration.
 *
 * <p>Each concern — resource, sampler, span processors, exporters — is a separate customizer bean
 * rather than a branch inside the assembling method, so supporting a new aspect means contributing
 * a bean instead of editing the assembly. Beans are applied in {@code @Order} order.
 *
 * <p>Applications may contribute their own; the built-in ones are ordered below
 * {@link OpenTelemetrySdkAutoConfiguration#EXPORTER_CUSTOMIZER_ORDER} so a later customizer sees a
 * fully populated builder.
 */
@FunctionalInterface
public interface SdkTracerProviderCustomizer {

    /**
     * Applies this customizer's aspect to the builder.
     *
     * @param builder the tracer provider builder being assembled
     */
    void customize(SdkTracerProviderBuilder builder);
}
