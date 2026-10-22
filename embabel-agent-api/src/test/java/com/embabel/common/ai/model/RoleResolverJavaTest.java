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
package com.embabel.common.ai.model;

import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The model kind SPI as a Java application uses it: a resolver and a credential factory built
 * from lambdas, returning each case of {@link RoleResolution}.
 */
class RoleResolverJavaTest {

    private static final ProviderCredential CREDENTIAL = new ProviderCredential("openai", "sk-test");

    @Test
    void aChatResolverCanReturnACredential() {
        RoleResolver<LlmService<?>, LlmOptions> resolver =
                RoleResolver.of(LlmService.KIND, (role, context) -> new RoleResolution.Credential(CREDENTIAL));

        // The kind is the constant passed in, which is what the platform matches on.
        assertSame(LlmService.KIND, resolver.getKind());
        // The lambda's answer comes back unchanged.
        assertEquals(new RoleResolution.Credential(CREDENTIAL), resolver.resolve("cheapest", ModelSelectionContext.EMPTY));
    }

    @Test
    void anEmbeddingResolverCanReturnABindingOrDelegate() {
        RoleResolver<EmbeddingService, ModelName> resolver = RoleResolver.of(
                EmbeddingService.KIND,
                (role, context) -> role.equals("documents") ? new RoleResolution.Bound<>(new ModelName("small")) : null);

        // A role the resolver handles gets its binding.
        assertEquals(
                new RoleResolution.Bound<>(new ModelName("small")),
                resolver.resolve("documents", ModelSelectionContext.EMPTY));
        // Any other role returns null, so the next resolver decides.
        assertNull(resolver.resolve("memory", ModelSelectionContext.EMPTY));
    }

    @Test
    void aServiceResolutionCanBeBuiltWithoutABinding() {
        EmbeddingService service = mock(EmbeddingService.class);
        var resolution = new RoleResolution.Service<EmbeddingService, ModelName>(service);

        // The single-argument constructor leaves the binding empty.
        assertSame(service, resolution.getService());
        assertNull(resolution.getBinding());
    }

    @Test
    void aCredentialFactoryCanDeclineAProvider() {
        CredentialModelFactory<EmbeddingService> factory =
                CredentialModelFactory.of(EmbeddingService.KIND, (credential, model) -> null);

        // The kind is the constant passed in.
        assertSame(EmbeddingService.KIND, factory.getKind());
        // Null means this factory does not handle the provider.
        assertNull(factory.create(CREDENTIAL, "small"));
    }
}
