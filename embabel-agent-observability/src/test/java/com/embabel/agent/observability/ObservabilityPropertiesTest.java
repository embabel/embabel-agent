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

import java.util.List;

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
        assertThat(props.getMaxAttributeLength()).isEqualTo(4000);
    }

    // Test trace flags default values
    @Test
    void traceFlags_shouldHaveCorrectDefaults() {
        ObservabilityProperties props = new ObservabilityProperties();

        assertThat(props.isTraceAgentEvents()).isTrue();
        assertThat(props.isTraceToolCalls()).isTrue();
        assertThat(props.isTraceToolLoop()).isTrue();
        assertThat(props.isTraceLlmCalls()).isTrue();
        assertThat(props.isTraceEmbedding()).isTrue();
        assertThat(props.isTracePlanning()).isTrue();
        assertThat(props.isTraceStateTransitions()).isTrue();
        assertThat(props.isTraceLifecycleStates()).isTrue();
        assertThat(props.isTraceRag()).isTrue();
        assertThat(props.isTraceRanking()).isTrue();
        assertThat(props.isTraceDynamicAgentCreation()).isTrue();
        assertThat(props.isTraceTrackedOperations()).isTrue();
        // HTTP details disabled by default (captures bodies/headers; opt-in only)
        assertThat(props.isTraceHttpDetails()).isFalse();
    }

    // Test umbrella switches and MDC defaults
    @Test
    void umbrellaSwitches_shouldHaveCorrectDefaults() {
        ObservabilityProperties props = new ObservabilityProperties();

        assertThat(props.isTracingEnabled()).isTrue();
        assertThat(props.isMetricsEnabled()).isTrue();
        assertThat(props.isMdcPropagation()).isTrue();
        assertThat(props.getDisabledTraces()).isEmpty();
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
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceHttpDetails(true);

        assertThat(props.isTraceHttpDetails()).isTrue();
    }

    @Test
    void setTraceToolLoop_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceToolLoop(false);

        assertThat(props.isTraceToolLoop()).isFalse();
    }

    @Test
    void setTraceEmbedding_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceEmbedding(false);

        assertThat(props.isTraceEmbedding()).isFalse();
    }

    @Test
    void setTraceTrackedOperations_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceTrackedOperations(false);

        assertThat(props.isTraceTrackedOperations()).isFalse();
    }

    @Test
    void setTracingEnabled_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracingEnabled(false);

        assertThat(props.isTracingEnabled()).isFalse();
    }

    @Test
    void setMetricsEnabled_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setMetricsEnabled(false);

        assertThat(props.isMetricsEnabled()).isFalse();
    }

    @Test
    void setMdcPropagation_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setMdcPropagation(false);

        assertThat(props.isMdcPropagation()).isFalse();
    }

    @Test
    void setDisabledTraces_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setDisabledTraces(List.of("tasks.scheduled.execution", "http.server.requests"));

        assertThat(props.getDisabledTraces())
                .containsExactly("tasks.scheduled.execution", "http.server.requests");
    }

    // null is coalesced to an empty list so callers never get an NPE
    @Test
    void setDisabledTraces_shouldCoalesceNullToEmptyList() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.setDisabledTraces(List.of("http.server.requests"));

        props.setDisabledTraces(null);

        assertThat(props.getDisabledTraces()).isNotNull().isEmpty();
    }

}
