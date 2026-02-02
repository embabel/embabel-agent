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
package com.embabel.agent.autoconfigure.chatstore;

import com.embabel.chat.ConversationFactory;
import com.embabel.chat.ConversationFactoryProvider;
import com.embabel.chat.MapConversationFactoryProvider;
import com.embabel.chat.store.adapter.StoredConversationFactory;
import com.embabel.chat.store.adapter.TitleGenerator;
import com.embabel.chat.store.repository.ChatSessionRepository;
import com.embabel.chat.support.InMemoryConversationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Auto-configuration for Embabel Chat Store.
 *
 * <p>Automatically activates when:
 * <ul>
 *   <li>Chat store classes are on the classpath</li>
 *   <li>{@code embabel.chat.store.enabled=true} (default)</li>
 * </ul>
 *
 * <p>This configuration provides:
 * <ul>
 *   <li>{@link StoredConversationFactory} - for persistent conversations</li>
 *   <li>{@link InMemoryConversationFactory} - for ephemeral conversations</li>
 * </ul>
 *
 * <p>Apps can override by defining their own {@link ConversationFactory} bean.
 *
 * <p>To disable entirely:
 * <pre>
 * embabel.chat.store.enabled=false
 * </pre>
 *
 * @since 0.3.3
 */
@AutoConfiguration
@ConditionalOnClass(ChatSessionRepository.class)
@ConditionalOnProperty(
    prefix = "embabel.chat.store",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(ChatStoreProperties.class)
public class ChatStoreAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ChatStoreAutoConfiguration.class);

    /**
     * Creates a {@link StoredConversationFactory} for persistent conversations.
     *
     * <p>Only created if:
     * <ul>
     *   <li>No existing {@link ConversationFactory} bean with qualifier "stored"</li>
     *   <li>{@link ChatSessionRepository} is available</li>
     * </ul>
     *
     * <p>Optionally wires in:
     * <ul>
     *   <li>{@link TitleGenerator} - for auto-generating session titles</li>
     *   <li>{@link ApplicationEventPublisher} - for message lifecycle events</li>
     * </ul>
     */
    @Bean("storedConversationFactory")
    @ConditionalOnMissingBean(name = "storedConversationFactory")
    public ConversationFactory storedConversationFactory(
            ChatSessionRepository repository,
            @Autowired(required = false) TitleGenerator titleGenerator,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {

        logger.info("Creating StoredConversationFactory (titleGenerator={}, eventPublisher={})",
            titleGenerator != null ? titleGenerator.getClass().getSimpleName() : "none",
            eventPublisher != null ? "present" : "none");

        return new StoredConversationFactory(repository, eventPublisher, titleGenerator);
    }

    /**
     * Creates an {@link InMemoryConversationFactory} for ephemeral conversations.
     *
     * <p>Only created if no existing {@link ConversationFactory} bean with qualifier "inMemory".
     */
    @Bean("inMemoryConversationFactory")
    @ConditionalOnMissingBean(name = "inMemoryConversationFactory")
    public ConversationFactory inMemoryConversationFactory(
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {

        logger.info("Creating InMemoryConversationFactory (eventPublisher={})",
            eventPublisher != null ? "present" : "none");

        return new InMemoryConversationFactory(eventPublisher);
    }

    /**
     * Creates a {@link ConversationFactoryProvider} that aggregates all available
     * {@link ConversationFactory} beans.
     *
     * <p>This provider enables looking up conversation factories by their
     * {@link com.embabel.chat.ConversationStoreType}.
     */
    @Bean
    @ConditionalOnMissingBean(ConversationFactoryProvider.class)
    public ConversationFactoryProvider conversationFactoryProvider(
            List<ConversationFactory> factories) {

        logger.info("Creating ConversationFactoryProvider with {} factories: {}",
            factories.size(),
            factories.stream().map(f -> f.getStoreType().name()).toList());

        return new MapConversationFactoryProvider(factories);
    }
}