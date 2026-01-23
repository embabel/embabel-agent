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
package com.embabel.agent.autoconfigure.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentPlatformAutoConfigurationFilter}.
 *
 * <p>
 * This test class validates the filtering logic that excludes Spring AI's default MCP client
 * auto-configuration while allowing all other auto-configurations to proceed. The tests ensure
 * proper handling of various scenarios including null entries, the target exclusion class,
 * and other auto-configuration classes.
 *
 * <h3>Testing Strategy</h3>
 * <ul>
 * <li><strong>Exclusion Validation:</strong> Verifies that the specific MCP client auto-configuration
 * is correctly filtered out</li>
 * <li><strong>Pass-through Validation:</strong> Ensures non-target auto-configurations are allowed</li>
 * <li><strong>Null Safety:</strong> Confirms safe handling of null entries in the input array</li>
 * <li><strong>Edge Cases:</strong> Tests empty arrays and mixed scenarios</li>
 * </ul>
 *
 * <h3>Design Pattern Analogy</h3>
 * Think of these tests as validating a <strong>Gateway or Gatekeeper pattern</strong>:
 * <ul>
 * <li>The filter acts as a gateway controlling which auto-configurations enter the application context</li>
 * <li>These tests verify the gateway's admission rules are correctly enforced</li>
 * <li>Like a bouncer at a club checking IDs, the filter must correctly identify and exclude specific entries</li>
 * </ul>
 *
 * @see AgentPlatformAutoConfigurationFilter
 * @see org.springframework.boot.autoconfigure.AutoConfigurationImportFilter
 */
class AgentPlatformAutoConfigurationFilterTest {

    private static final String MCP_CLIENT_AUTO_CONFIGURATION =
            "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration";

    private AgentPlatformAutoConfigurationFilter filter;
    private AutoConfigurationMetadata metadata;

    @BeforeEach
    void setUp() {
        filter = new AgentPlatformAutoConfigurationFilter();
        // Metadata is not used in the implementation, so a mock is sufficient
        metadata = Mockito.mock(AutoConfigurationMetadata.class);
    }

    /**
     * Tests that the target MCP client auto-configuration is correctly excluded.
     *
     * <p>
     * This is the primary use case - ensuring that Spring AI's default MCP client
     * configuration is filtered out so the Agent Platform's resilient version can
     * take its place.
     */
    @Test
    void shouldExcludeMcpClientAutoConfiguration() {
        String[] autoConfigurations = {MCP_CLIENT_AUTO_CONFIGURATION};

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(1);
        assertThat(matches[0]).isFalse();
    }

    /**
     * Tests that other auto-configurations are allowed through.
     *
     * <p>
     * The filter should only exclude the specific MCP client configuration and
     * allow all other auto-configurations to load normally.
     */
    @Test
    void shouldAllowOtherAutoConfigurations() {
        String[] autoConfigurations = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
                "com.example.CustomAutoConfiguration"
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(3);
        assertThat(matches[0]).isTrue();
        assertThat(matches[1]).isTrue();
        assertThat(matches[2]).isTrue();
    }

    /**
     * Tests mixed scenario with both excluded and allowed configurations.
     *
     * <p>
     * Validates that the filter correctly processes an array containing both the
     * target exclusion and other auto-configurations, filtering only the specific
     * MCP client configuration.
     */
    @Test
    void shouldHandleMixedAutoConfigurations() {
        String[] autoConfigurations = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                MCP_CLIENT_AUTO_CONFIGURATION,
                "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.QuiteMcpClientAutoConfiguration"
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(4);
        assertThat(matches[0]).isTrue();  // DataSourceAutoConfiguration - allowed
        assertThat(matches[1]).isFalse(); // McpClientAutoConfiguration - excluded
        assertThat(matches[2]).isTrue();  // WebMvcAutoConfiguration - allowed
        assertThat(matches[3]).isTrue();  // QuiteMcpClientAutoConfiguration - allowed
    }

    /**
     * Tests that null entries in the array are handled safely.
     *
     * <p>
     * Spring Boot's internal processing may occasionally include null entries in
     * the auto-configuration array. The filter must handle these gracefully without
     * throwing NullPointerException.
     */
    @Test
    void shouldHandleNullEntries() {
        String[] autoConfigurations = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                null,
                "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration"
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(3);
        assertThat(matches[0]).isTrue();  // DataSourceAutoConfiguration - allowed
        assertThat(matches[1]).isFalse(); // null - excluded for safety
        assertThat(matches[2]).isTrue();  // WebMvcAutoConfiguration - allowed
    }

    /**
     * Tests that null entries mixed with the target exclusion are handled correctly.
     *
     * <p>
     * Validates the interaction between null handling and exclusion logic.
     */
    @Test
    void shouldHandleNullEntriesAndExcludedConfiguration() {
        String[] autoConfigurations = {
                null,
                MCP_CLIENT_AUTO_CONFIGURATION,
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                null
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(4);
        assertThat(matches[0]).isFalse(); // null - excluded
        assertThat(matches[1]).isFalse(); // McpClientAutoConfiguration - excluded
        assertThat(matches[2]).isTrue();  // DataSourceAutoConfiguration - allowed
        assertThat(matches[3]).isFalse(); // null - excluded
    }

    /**
     * Tests behavior with an empty array.
     *
     * <p>
     * Although unlikely in practice, the filter should handle an empty input array
     * gracefully and return an empty result array.
     */
    @Test
    void shouldHandleEmptyArray() {
        String[] autoConfigurations = {};

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).isEmpty();
    }

    /**
     * Tests that the returned array has the same length as the input array.
     *
     * <p>
     * This is a critical contract requirement - the boolean array must have exactly
     * one entry for each input auto-configuration class name.
     */
    @Test
    void shouldReturnArrayWithSameLengthAsInput() {
        String[] autoConfigurations = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
                MCP_CLIENT_AUTO_CONFIGURATION,
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                null,
                "com.example.CustomAutoConfiguration"
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(autoConfigurations.length);
    }

    /**
     * Tests that similar but non-matching class names are allowed.
     *
     * <p>
     * Ensures the filter uses exact string matching and doesn't accidentally exclude
     * configurations with similar names.
     */
    @Test
    void shouldAllowSimilarlyNamedConfigurations() {
        String[] autoConfigurations = {
                "org.springframework.ai.mcp.client.MyMcpClientAutoConfiguration",
                "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.QuiteMcpClientAutoConfiguration"
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(3);
        assertThat(matches[0]).isTrue(); // Different package/name - allowed
        assertThat(matches[1]).isTrue(); // Different class in same package - allowed
        assertThat(matches[2]).isTrue(); // Our replacement - allowed
    }

    /**
     * Tests that the filter doesn't use the metadata parameter.
     *
     * <p>
     * The current implementation doesn't use the metadata parameter. This test
     * documents that behavior and ensures it remains consistent even with null metadata.
     */
    @Test
    void shouldWorkWithNullMetadata() {
        String[] autoConfigurations = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                MCP_CLIENT_AUTO_CONFIGURATION
        };

        boolean[] matches = filter.match(autoConfigurations, null);

        assertThat(matches).hasSize(2);
        assertThat(matches[0]).isTrue();
        assertThat(matches[1]).isFalse();
    }

    /**
     * Tests filtering with only null entries.
     *
     * <p>
     * Edge case validation ensuring the filter handles an array of all nulls gracefully.
     */
    @Test
    void shouldHandleAllNullEntries() {
        String[] autoConfigurations = {null, null, null};

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(3);
        assertThat(matches[0]).isFalse();
        assertThat(matches[1]).isFalse();
        assertThat(matches[2]).isFalse();
    }

    /**
     * Tests that multiple occurrences of the target configuration are all excluded.
     *
     * <p>
     * Although unlikely in practice, if the same auto-configuration appears multiple
     * times in the array, all occurrences should be filtered out.
     */
    @Test
    void shouldExcludeMultipleOccurrencesOfTargetConfiguration() {
        String[] autoConfigurations = {
                MCP_CLIENT_AUTO_CONFIGURATION,
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                MCP_CLIENT_AUTO_CONFIGURATION
        };

        boolean[] matches = filter.match(autoConfigurations, metadata);

        assertThat(matches).hasSize(3);
        assertThat(matches[0]).isFalse(); // First occurrence - excluded
        assertThat(matches[1]).isTrue();  // Other config - allowed
        assertThat(matches[2]).isFalse(); // Second occurrence - excluded
    }
}