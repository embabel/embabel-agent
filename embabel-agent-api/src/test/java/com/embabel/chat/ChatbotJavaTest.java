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
package com.embabel.chat;

import com.embabel.agent.api.channel.DevNullOutputChannel;
import com.embabel.agent.api.channel.OutputChannel;
import com.embabel.agent.api.identity.User;
import com.embabel.chat.support.InMemoryConversation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for the Chatbot API.
 * Verifies that Chatbot and ChatSession can be implemented from Java.
 */
class ChatbotJavaTest {

    @Test
    void canImplementChatbotInJava() {
        Chatbot chatbot = new SimpleChatbot();

        ChatSession session = chatbot.createSession(
            null,
            DevNullOutputChannel.INSTANCE,
            null,
            null
        );

        assertNotNull(session);
        assertNotNull(session.getConversation());
    }

    @Test
    void canImplementChatbotWithContextId() {
        Chatbot chatbot = new SimpleChatbot();

        ChatSession session = chatbot.createSession(
            null,
            DevNullOutputChannel.INSTANCE,
            "test-context-id",
            null
        );

        assertNotNull(session);
    }

    @Test
    void canImplementChatbotWithConversationId() {
        Chatbot chatbot = new SimpleChatbot();

        ChatSession session = chatbot.createSession(
            null,
            DevNullOutputChannel.INSTANCE,
            null,
            "saved-conversation-id"
        );

        assertNotNull(session);
    }

    @Test
    void canFindSession() {
        SimpleChatbot chatbot = new SimpleChatbot();

        ChatSession session = chatbot.createSession(
            null,
            DevNullOutputChannel.INSTANCE,
            null,
            null
        );

        ChatSession found = chatbot.findSession("test-conversation");
        assertNotNull(found);
        assertEquals(session, found);
    }

    /**
     * Simple Chatbot implementation in Java for testing.
     */
    static class SimpleChatbot implements Chatbot {

        private ChatSession lastSession;

        @NotNull
        @Override
        public ChatSession createSession(
            @Nullable User user,
            @NotNull OutputChannel outputChannel,
            @Nullable String contextId,
            @Nullable String conversationId
        ) {
            lastSession = new SimpleChatSession(user, outputChannel);
            return lastSession;
        }

        @Nullable
        @Override
        public ChatSession findSession(@NotNull String conversationId) {
            return lastSession;
        }
    }

    /**
     * Simple ChatSession implementation in Java for testing.
     */
    static class SimpleChatSession implements ChatSession {

        private final User user;
        private final OutputChannel outputChannel;
        private final Conversation conversation;

        SimpleChatSession(User user, OutputChannel outputChannel) {
            this.user = user;
            this.outputChannel = outputChannel;
            this.conversation = new InMemoryConversation(
                java.util.Collections.emptyList(),
                "test-conversation"
            );
        }

        @NotNull
        @Override
        public OutputChannel getOutputChannel() {
            return outputChannel;
        }

        @Nullable
        @Override
        public User getUser() {
            return user;
        }

        @NotNull
        @Override
        public Conversation getConversation() {
            return conversation;
        }

        @Override
        public void onUserMessage(@NotNull UserMessage userMessage) {
            conversation.addMessage(userMessage);
        }

        @Override
        public void onTrigger(@NotNull ChatTrigger trigger) {
            // No-op for tests
        }
    }
}
