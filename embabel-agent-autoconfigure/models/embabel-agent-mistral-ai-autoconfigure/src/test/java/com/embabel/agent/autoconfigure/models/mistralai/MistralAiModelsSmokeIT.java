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
package com.embabel.agent.autoconfigure.models.mistralai;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: calls <b>every</b> registered Mistral model against the real API with a trivial prompt,
 * each must return a non-blank response. Iterates the models from the bundled YAML so it stays in sync
 * with the catalogue automatically.
 *
 * <p>Only runs when {@code MISTRAL_API_KEY} is set — one real, billable call per model.
 */
@EnabledIfEnvironmentVariable(
        named = "MISTRAL_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires MISTRAL_API_KEY and makes real calls to api.mistral.ai")
class MistralAiModelsSmokeIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
            .withPropertyValues(
                    "embabel.agent.platform.models.mistralai.max-attempts=1",
                    "embabel.agent.platform.http-client.read-timeout=5m"
            );

    @Test
    void everyRegisteredMistralModelResponds() {
        contextRunner.run(context -> {
            var models = new TreeMap<String, SpringAiLlmService>();
            context.getBeansOfType(SpringAiLlmService.class).values()
                    .forEach(s -> models.put(s.getName(), s));

            assertThat(models).as("no Mistral models were registered").isNotEmpty();

            var failures = new ArrayList<String>();
            var report = new StringBuilder("\nMistral model smoke test:\n");

            for (Map.Entry<String, SpringAiLlmService> entry : models.entrySet()) {
                var modelId = entry.getKey();
                var start = System.nanoTime();
                try {
                    var response = entry.getValue().getChatModel().call("Reply with exactly the word READY.");
                    var elapsed = Duration.ofNanos(System.nanoTime() - start);
                    if (response == null || response.isBlank()) {
                        failures.add(modelId + " (blank response)");
                        report.append(String.format("  FAIL  %-24s blank response (%s)%n", modelId, elapsed));
                    } else {
                        report.append(String.format("  OK    %-24s %s%n", modelId, elapsed));
                    }
                } catch (Exception ex) {
                    var elapsed = Duration.ofNanos(System.nanoTime() - start);
                    var cause = rootCause(ex);
                    failures.add(modelId + " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")");
                    report.append(String.format("  FAIL  %-24s %s after %s -> %s: %s%n",
                            modelId, cause.getClass().getSimpleName(), elapsed,
                            cause.getClass().getSimpleName(), cause.getMessage()));
                    // Full stack trace so a single run pins the exact throw site of the failure.
                    System.out.println("---- full stack trace for " + modelId + " ----");
                    ex.printStackTrace(System.out);
                }
            }

            System.out.println(report);
            assertThat(failures)
                    .as("models that did not return a valid response: %s", failures)
                    .isEmpty();
        });
    }

    private static Throwable rootCause(Throwable t) {
        var current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
