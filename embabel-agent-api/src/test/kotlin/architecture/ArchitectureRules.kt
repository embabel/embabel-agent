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
package architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Tag


/**
 * To run:
 *
 * mvn test -Dtest=architecture.ArchitectureRules -Dsurefire.failIfNoSpecifiedTests=false >/tmp/arch-rules
 *
 *  Reference Guide :
 *  https://www.archunit.org/userguide/html/000_Index.html
 *
 */
@Tag("architecture")
@AnalyzeClasses(
    packages = ["com.embabel.agent"],
    importOptions = [ExcludeExperimentalOption::class, ImportOption.DoNotIncludeTests::class]
)
class ArchitectureRules {

    @ArchTest
    val noPackageCycles = slices()
        .matching("com.embabel.agent.(*)..")
        .should().beFreeOfCycles()

    @ArchTest
    val noClassCycles = slices()
        .matching("com.embabel.agent.(*)")
        .should().beFreeOfCycles()

    @ArchTest
    val coreShouldNotDependOnApi =
        noClasses().that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAPackage("..api..")

    @ArchTest
    val apiShouldNotDependOnSpi =
        noClasses().that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..spi..")

}

class ExcludeExperimentalOption : ImportOption {

    override fun includes(location: Location?): Boolean {
        return location?.contains("experimental") == false;
    }

}
