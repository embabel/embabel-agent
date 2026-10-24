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
package com.embabel.agent.a2a.architecture

import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import org.junit.jupiter.api.Tag
import org.springframework.stereotype.Service
import javax.annotation.concurrent.GuardedBy
import javax.annotation.concurrent.Immutable
import javax.annotation.concurrent.ThreadSafe

/**
 * The A2A services are singletons shared by concurrent requests and streaming threads.
 */
@Tag("architecture")
@AnalyzeClasses(
    packages = ["com.embabel.agent.a2a"],
    importOptions = [ImportOption.DoNotIncludeTests::class]
)
class A2AThreadSafetyRulesTest {

    @ArchTest
    val servicesShouldBeThreadSafe: ArchRule =
        classes().that().areAnnotatedWith(Service::class.java)
            .should().beAnnotatedWith(ThreadSafe::class.java)
            .because("A2A services are called concurrently by request and streaming threads")

    @ArchTest
    val threadSafeClassesShouldNotHaveUnguardedMutableFields: ArchRule =
        fields().that().areNotStatic()
            .and().areDeclaredInClassesThat(annotatedWith(ThreadSafe::class.java).or(annotatedWith(Immutable::class.java)))
            .should().beFinal()
            .orShould().beAnnotatedWith(GuardedBy::class.java)
            .because("@ThreadSafe and @Immutable classes must not have mutable fields without @GuardedBy")
}
