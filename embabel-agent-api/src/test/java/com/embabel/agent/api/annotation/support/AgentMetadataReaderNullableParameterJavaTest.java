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
package com.embabel.agent.api.annotation.support;

import com.embabel.agent.core.AgentScope;
import com.embabel.agent.domain.io.UserInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test nullable parameter handling for Java classes.
 * This test is in Java because Kotlin compiles first and the Java test classes
 * wouldn't be available to the Kotlin tests.
 */
class AgentMetadataReaderNullableParameterJavaTest {

    @Test
    void oneActionWithNullableParameterMetadataJavaSpring() {
        testNullableParameter(new OneTransformerActionWithNullableParameterJavaSpring());
    }

    @Test
    void oneActionWithNullableParameterMetadataJavaJakarta() {
        testNullableParameter(new OneTransformerActionWithNullableParameterJavaJakarta());
    }
    
    private void testNullableParameter(Object instance) {
        AgentMetadataReader reader = new AgentMetadataReader();
        AgentScope metadata = reader.createAgentMetadata(instance);
        assertNotNull(metadata);
        assertEquals(1, metadata.getActions().size());
        var action = metadata.getActions().getFirst();
        assertEquals(1, action.getInputs().size(), "Should have 1 input as nullable doesn't count in Java");
        var input = action.getInputs().iterator().next();
        // Extract type from IoBinding value which is in format "name:type" or just "type"
        String inputValue = input.getValue();
        String inputType = inputValue.contains(":") ? inputValue.split(":")[1] : inputValue;
        assertEquals(UserInput.class.getName(), inputType);

        assertEquals(1, action.getOutputs().size(), "Should have 1 output");
        var output = action.getOutputs().iterator().next();
        String outputValue = output.getValue();
        String outputType = outputValue.contains(":") ? outputValue.split(":")[1] : outputValue;
        assertEquals(
                PersonWithReverseTool.class.getName(),
                outputType,
                "Output name must match"
        );
    }
}
