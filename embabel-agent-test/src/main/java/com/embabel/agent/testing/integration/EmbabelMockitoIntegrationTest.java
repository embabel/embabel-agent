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
import com.embabel.chat.Message;
import com.embabel.common.ai.model.ModelProvider;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Base class for integration tests that use Mockito to mock LLM operations.
 * Provides convenient methods for stubbing and verifying LLM interactions.
 * Subclasses will be Spring Boot tests that start the AgentPlatform.
 * Prompt matching is done with normal lambdas rather than Mockito.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "embabel.models.default-llm=test-model",
        "embabel.agent.verbosity.debug=true",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false"
})
public class EmbabelMockitoIntegrationTest {

    @Autowired
    protected AgentPlatform agentPlatform;

    @MockitoBean
    private ModelProvider modelProvider;

    @MockitoBean
    protected LlmOperations llmOperations;

    // Stubbing methods
    protected <T> OngoingStubbing<T> whenCreateObject(Predicate<String> promptMatcher, Class<T> outputClass, Predicate<LlmInteraction> llmInteractionPredicate) {
        // Mock the lower level LLM operation to create an object
        // that will ultimately be called
        return when(llmOperations.createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionPredicate::test), eq(outputClass), any(), any()));
    }

    protected <T> OngoingStubbing<T> whenCreateObject(Predicate<String> promptMatcher, Class<T> outputClass) {
        return whenCreateObject(promptMatcher, outputClass, llmi -> true);
    }

    protected OngoingStubbing<String> whenGenerateText(Predicate<String> promptMatcher, Predicate<LlmInteraction> llmInteractionMatcher) {
        return when(llmOperations.createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)),
                argThat(llmInteractionMatcher::test), eq(String.class), any(), any()));
    }

    protected OngoingStubbing<String> whenGenerateText(Predicate<String> promptMatcher) {
        return whenGenerateText(promptMatcher, llmi -> true);
    }

    // Verification methods
    protected <T> void verifyCreateObject(Predicate<String> promptMatcher, Class<T> outputClass, Predicate<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)),
                argThat(llmInteractionMatcher::test), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObject(Predicate<String> prompt, Class<T> outputClass) {
        verifyCreateObject(prompt, outputClass, llmi -> true);
    }

    protected void verifyGenerateText(Predicate<String> promptMatcher, Predicate<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionMatcher::test), eq(String.class), any(), any());
    }

    protected void verifyGenerateText(Predicate<String> promptMatcher) {
        verifyGenerateText(promptMatcher, llmi -> true);
    }

    // Verification methods with argument matchers
    protected <T> void verifyCreateObjectMatching(Predicate<String> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionMatcher), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectMatchingMessages(ArgumentMatcher<List<Message>> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(promptMatcher),
                argThat(llmInteractionMatcher),
                eq(outputClass), any(), any());
    }

    protected void verifyGenerateTextMatching(Predicate<String> promptMatcher) {
        verify(llmOperations).createObject(argThat(messages -> firstMessageContentSatisfiesMatcher(messages, promptMatcher)), any(), eq(String.class), any(), any());
    }

    protected void verifyGenerateTextMatching(Predicate<String> promptMatcher,
                                              LlmInteraction llmInteraction) {
        Mockito.verify(llmOperations).createObject(argThat(messages -> firstMessageContentSatisfiesMatcher(messages, promptMatcher)), eq(llmInteraction), eq(String.class), any(), any());
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


    private boolean firstMessageContentSatisfiesMatcher(List<? extends Message> messages, Predicate<String> contentMatcher) {
        return messages != null && messages.size() == 1 && contentMatcher.test(messages.getFirst().getContent());
    }

}