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

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Tag
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility


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
        noClasses().that(
            arePublicAndResideInPackage("..core..")
        ).should().dependOnClassesThat(
            arePublicAndResideInPackage("..api..")
        )


    @ArchTest
    val apiShouldNotDependOnSpi =
        noClasses().that(
            arePublicAndResideInPackage("..api..")
        ).should().dependOnClassesThat(
            arePublicAndResideInPackage("..spi..")
        )

    private fun arePublicAndResideInPackage(
        packageIdentifier: String,
    ): DescribedPredicate<JavaClass> =
        areKotlinPublic()
            .and(not(annotatedWith(ApiStatus.Internal::class.java)))
            .and(
                resideInAPackage(packageIdentifier)
                    .and(not(resideInAPackage("..spring..")))
            )



    private fun areKotlinPublic(): DescribedPredicate<JavaClass> =
        object : DescribedPredicate<JavaClass>("are public") {
            override fun test(javaClass: JavaClass): Boolean {
                if (!javaClass.isAnnotatedWith("kotlin.Metadata")) {
                    return true;
                }
                val annotation: JavaAnnotation<JavaClass> = javaClass.getAnnotationOfType("kotlin.Metadata")

                val metadata = Metadata(
                    kind = annotation.get("k").get() as Int,
                    metadataVersion = annotation.get("mv").get() as IntArray,
                    bytecodeVersion = annotation.get("bv").get() as IntArray,
                    data1 = annotation.get("d1").get() as Array<String>,
                    data2 = annotation.get("d2").get() as Array<String>,
                    extraString = annotation.get("xs").orElse(null) as String,
                    packageName = annotation.get("pn").orElse(null) as String,
                    extraInt = annotation.get("xi").orElse(0) as Int
                )

                val kotlinClassMetadata = KotlinClassMetadata.readLenient(metadata)
                if (kotlinClassMetadata !is KotlinClassMetadata.Class) {
                    return true
                }
                return kotlinClassMetadata.kmClass.visibility == Visibility.PUBLIC;
            };
        }




}

class ExcludeExperimentalOption : ImportOption {

    override fun includes(location: Location?): Boolean {
        return location?.contains("experimental") == false;
    }

}
