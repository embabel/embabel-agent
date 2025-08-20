/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.testing.integration;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.spi.LlmInteraction;
import com.embabel.agent.spi.LlmOperations;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.verification.VerificationMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "embabel.agent.llm.default-model=test-model",
        "embabel.agent.verbosity.debug=true",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false"
})
class EmbabelMockitoIntegrationTest {

    @Autowired
    protected AgentPlatform agentPlatform;

    @MockitoBean
    protected LlmOperations llmOperations;

    // Stubbing methods
    protected <T> OngoingStubbing<T> whenCreateObject(String prompt, Class<T> outputClass, LlmInteraction llmInteraction) {
        // Mock the lower level LLM operation to create an object
        // that will ultimately be called
        return when(llmOperations.createObject(prompt, llmInteraction, eq(outputClass), any(), any()));
    }

    protected <T> OngoingStubbing<T> whenCreateObject(String prompt, Class<T> outputClass) {
        return when(llmOperations.createObject(prompt, any(), eq(outputClass), any(), any()));
    }

    protected OngoingStubbing<String> whenGenerateText(String prompt, LlmInteraction llmInteraction) {
        return when(llmOperations.createObject(prompt, llmInteraction, eq(String.class), any(), any()));
    }

    protected OngoingStubbing<String> whenGenerateText(String prompt) {
        return when(llmOperations.createObject(prompt, any(), eq(String.class), any(), any()));
    }

    // Verification methods
    protected <T> void verifyCreateObject(String prompt, Class<T> outputClass, LlmInteraction llmInteraction) {
        verify(llmOperations).createObject(eq(prompt), eq(llmInteraction), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObject(String prompt, Class<T> outputClass) {
        verify(llmOperations).createObject(eq(prompt), any(), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObject(String prompt, Class<T> outputClass, LlmInteraction llmInteraction, VerificationMode mode) {
        verify(llmOperations, mode).createObject(eq(prompt), eq(llmInteraction), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObject(String prompt, Class<T> outputClass, VerificationMode mode) {
        verify(llmOperations, mode).createObject(eq(prompt), any(), eq(outputClass), any(), any());
    }

    protected void verifyGenerateText(String prompt, LlmInteraction llmInteraction) {
        verify(llmOperations).createObject(eq(prompt), eq(llmInteraction), eq(String.class), any(), any());
    }

    protected void verifyGenerateText(String prompt) {
        verify(llmOperations).createObject(eq(prompt), any(), eq(String.class), any(), any());
    }

    protected void verifyGenerateText(String prompt, LlmInteraction llmInteraction, VerificationMode mode) {
        verify(llmOperations, mode).createObject(eq(prompt), eq(llmInteraction), eq(String.class), any(), any());
    }

    protected void verifyGenerateText(String prompt, VerificationMode mode) {
        verify(llmOperations, mode).createObject(eq(prompt), any(), eq(String.class), any(), any());
    }

    // Verification methods with argument matchers
    protected <T> void verifyCreateObjectMatching(ArgumentMatcher<String> promptMatcher, Class<T> outputClass) {
        verify(llmOperations).createObject(argThat(promptMatcher), any(), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectMatching(ArgumentMatcher<String> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(promptMatcher),
                argThat(llmInteractionMatcher),
                eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectMatching(ArgumentMatcher<String> promptMatcher, Class<T> outputClass, LlmInteraction llmInteraction) {
        verify(llmOperations).createObject(argThat(promptMatcher), eq(llmInteraction), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectMatching(ArgumentMatcher<String> promptMatcher, Class<T> outputClass, VerificationMode mode) {
        verify(llmOperations, mode).createObject(argThat(promptMatcher), any(), eq(outputClass), any(), any());
    }

    protected void verifyGenerateTextMatching(ArgumentMatcher<String> promptMatcher) {
        verify(llmOperations).createObject(argThat(promptMatcher), any(), eq(String.class), any(), any());
    }

    protected void verifyGenerateTextMatching(ArgumentMatcher<String> promptMatcher, LlmInteraction llmInteraction) {
        Mockito.verify(llmOperations).createObject(argThat(promptMatcher), eq(llmInteraction), eq(String.class), any(), any());
    }

    protected void verifyGenerateTextMatching(ArgumentMatcher<String> promptMatcher, VerificationMode mode) {
        Mockito.verify(llmOperations, mode).createObject(argThat(promptMatcher), any(), eq(String.class), any(), any());
    }

    // Convenience verification methods
    protected void verifyNoInteractions() {
        Mockito.verifyNoInteractions(llmOperations);
    }

    protected void verifyNoMoreInteractions() {
        Mockito.verifyNoMoreInteractions(llmOperations);
    }

    // Argument captor helpers
    protected ArgumentCaptor<String> capturePrompt() {
        return ArgumentCaptor.forClass(String.class);
    }

    protected ArgumentCaptor<LlmInteraction> captureLlmInteraction() {
        return ArgumentCaptor.forClass(LlmInteraction.class);
    }

    protected <T> ArgumentCaptor<Class<T>> captureOutputClass() {
        return ArgumentCaptor.forClass(Class.class);
    }
}