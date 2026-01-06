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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.Ai
import jakarta.validation.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import kotlin.reflect.KClass

data class Palindromic(
    @field:MustBePalindrome
    val eats: String,
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PalindromeValidator::class])
annotation class MustBePalindrome(
    val message: String = "Must be a palindrome",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

@Component
class PalindromeValidator(
    val ai: Ai,
) : ConstraintValidator<MustBePalindrome, String> {

    override fun isValid(
        field: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        assertNotNull(ai, "AI instance should be injected")
        return field != null && field == field.reversed()
    }
}


@SpringBootTest
@ActiveProfiles(value = ["test"])
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
@Import(
    value = [

    ]
)
class CustomValidationAnnotationTest {

    @Autowired
    private lateinit var validator: Validator

    @Test
    fun `custom annotated validated field with violation`() {
        val invalidPalindromic = Palindromic("able was i ere i saw st helena")
        val violations = validator.validate(invalidPalindromic)
        assert(violations.isNotEmpty())
    }

    @Test
    fun `custom annotated validated field without violation`() {
        val validPalindromic = Palindromic("able was i ere i saw elba")
        val violations = validator.validate(validPalindromic)
        assert(violations.isEmpty())
    }

}
