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
package com.embabel.agent.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ObservabilityProperties configuration.
 */
class ObservabilityPropertiesTest {

    // Test default values are correctly set
    @Test
    void defaultValues_shouldBeCorrect() {
        ObservabilityProperties props = new ObservabilityProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getServiceName()).isEqualTo("embabel-agent");
        assertThat(props.getTracerName()).isEqualTo("embabel-agent");
        assertThat(props.getTracerVersion()).isEqualTo("0.3.4");
        assertThat(props.getMaxAttributeLength()).isEqualTo(4000);
    }

    // Test trace flags default values
    @Test
    void traceFlags_shouldHaveCorrectDefaults() {
        ObservabilityProperties props = new ObservabilityProperties();

        assertThat(props.isTraceAgentEvents()).isTrue();
        assertThat(props.isTraceToolCalls()).isTrue();
        assertThat(props.isTraceLlmCalls()).isTrue();
        assertThat(props.isTracePlanning()).isTrue();
        assertThat(props.isTraceStateTransitions()).isTrue();
        assertThat(props.isTraceLifecycleStates()).isTrue();
        assertThat(props.isTraceRag()).isTrue();
        assertThat(props.isTraceRanking()).isTrue();
        assertThat(props.isTraceDynamicAgentCreation()).isTrue();
        // HTTP details enabled by default
        assertThat(props.isTraceHttpDetails()).isTrue();
    }

    // Test setters work correctly
    @Test
    void setters_shouldUpdateValues() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setEnabled(false);
        props.setServiceName("custom-service");
        props.setMaxAttributeLength(1000);
        props.setTraceToolCalls(false);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getServiceName()).isEqualTo("custom-service");
        assertThat(props.getMaxAttributeLength()).isEqualTo(1000);
        assertThat(props.isTraceToolCalls()).isFalse();
    }

    // Test tracerName setter
    @Test
    void setTracerName_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracerName("custom-tracer");

        assertThat(props.getTracerName()).isEqualTo("custom-tracer");
    }

    // Test tracerVersion setter
    @Test
    void setTracerVersion_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracerVersion("1.0.0");

        assertThat(props.getTracerVersion()).isEqualTo("1.0.0");
    }

    // Test all trace flag setters
    @Test
    void setTraceAgentEvents_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceAgentEvents(false);

        assertThat(props.isTraceAgentEvents()).isFalse();
    }

    @Test
    void setTraceLlmCalls_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceLlmCalls(false);

        assertThat(props.isTraceLlmCalls()).isFalse();
    }

    @Test
    void setTracePlanning_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracePlanning(false);

        assertThat(props.isTracePlanning()).isFalse();
    }

    @Test
    void setTraceStateTransitions_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceStateTransitions(false);

        assertThat(props.isTraceStateTransitions()).isFalse();
    }

    @Test
    void setTraceLifecycleStates_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceLifecycleStates(false);

        assertThat(props.isTraceLifecycleStates()).isFalse();
    }

    @Test
    void setTraceRag_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceRag(false);

        assertThat(props.isTraceRag()).isFalse();
    }

    @Test
    void setTraceRanking_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceRanking(false);

        assertThat(props.isTraceRanking()).isFalse();
    }

    @Test
    void setTraceDynamicAgentCreation_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceDynamicAgentCreation(false);

        assertThat(props.isTraceDynamicAgentCreation()).isFalse();
    }

    @Test
    void setTraceHttpDetails_shouldUpdateValue() {
        var props = new ObservabilityProperties();

        props.setTraceHttpDetails(true);

        assertThat(props.isTraceHttpDetails()).isTrue();
    }

}
