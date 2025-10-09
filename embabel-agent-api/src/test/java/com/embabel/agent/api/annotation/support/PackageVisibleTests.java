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
package com.embabel.agent.api.annotation.support;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlatformServices;
import com.embabel.agent.channel.DevNullOutputChannel;
import com.embabel.agent.core.ActionStatusCode;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessContext;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.support.InMemoryBlackboard;
import com.embabel.agent.core.support.SimpleAgentProcess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform;
import static com.embabel.agent.testing.integration.IntegrationTestUtils.dummyPlatformServices;
import static com.embabel.common.core.types.Semver.DEFAULT_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Need a Java test because there's no equivalent in Kotlin
 */
class PackageVisibleTests {

    @Test
    public void actionInvocationWithJavaPackageVisibleParameters() {
        AgentMetadataReader reader = new AgentMetadataReader();
        var metadata = reader.createAgentMetadata(new JavaPackageVisibleDomainClasses());
        assertNotNull(metadata);
        assertEquals(1, metadata.getActions().size(), "Should have exactly 1 action");

        var action = metadata.getActions().getFirst();
        var agent = new com.embabel.agent.core.Agent(
                "name",
                "provider",
                DEFAULT_VERSION,
                "description",
                Set.of(),
                List.of(action),
                Set.of()
        );
        PlatformServices platformServices = dummyPlatformServices();
        var processOptions = ProcessOptions.DEFAULT;

        var pc = new ProcessContext(
                processOptions,
                platformServices,
                DevNullOutputChannel.INSTANCE,
                new SimpleAgentProcess(
                        "test",
                        null,
                        agent,
                        processOptions,
                        new InMemoryBlackboard(),
                        platformServices,
                        Instant.now()
                )
        );

        pc.getBlackboard().plusAssign(new PackageInput("John Doe"));
        var result = action.execute(pc);
        assertEquals(ActionStatusCode.SUCCEEDED, result.getStatus());
        assertEquals(new PackageOutput("John Doe"), pc.getBlackboard().lastResult());
    }

    @Test
    public void conditionInvocationWithJavaPackageVisibleConditionMethod() {
        AgentMetadataReader reader = new AgentMetadataReader();
        var metadata = reader.createAgentMetadata(new OneOperationContextConditionOnlyJavaInternal());
        assertNotNull(metadata);
        assertEquals(1, metadata.getConditions().size(), "Should have 1 condition");

        var agent = (com.embabel.agent.core.Agent) metadata;
        PlatformServices platformServices = dummyPlatformServices();
        var processOptions = ProcessOptions.DEFAULT;

        var pc = new ProcessContext(
                processOptions,
                platformServices,
                DevNullOutputChannel.INSTANCE,
                new SimpleAgentProcess(
                        "test",
                        null,
                        agent,
                        processOptions,
                        new InMemoryBlackboard(),
                        platformServices,
                        Instant.now()
                )
        );

        pc.getBlackboard().plusAssign(new PackageInput("John Doe"));
        var dap = dummyAgentPlatform();
        var agentProcess = dap.runAgentFrom(agent, processOptions, Map.of(
                "it", new PackageInput("content")
        ));
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.getStatus());
    }

}

record PackageInput(String content) {
}

record PackageOutput(String content) {
}

@Agent(description = "Package visible domain classes")
class JavaPackageVisibleDomainClasses {

    @Action(cost = 500.0)
    PackageOutput oo(PackageInput packageInput) {
        return new PackageOutput(packageInput.content());
    }

}


@com.embabel.agent.api.annotation.Agent(description = "foo bar")
class OneOperationContextConditionOnlyJavaInternal {

    @Condition(cost = .5)
    boolean condition1(OperationContext operationContext) {
        return true;
    }


    @AchievesGoal(description = "getting something done")
    @Action(pre = {"condition1"})
    PackageOutput oo(PackageInput packageInput) {
        return new PackageOutput(packageInput.content());
    }

}