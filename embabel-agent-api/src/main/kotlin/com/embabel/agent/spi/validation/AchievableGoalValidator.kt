package com.embabel.agent.spi.validation

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.core.AgentScope
import com.embabel.common.core.validation.ValidationError
import com.embabel.common.core.validation.ValidationErrorCodes
import com.embabel.common.core.validation.ValidationLocation
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import org.slf4j.LoggerFactory
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Validator that checks methods annotated with AchievesGoal.
 * Specific check includes:
 *  - Verifying that @Action annotation is present on it.
 */
class AchievableGoalValidator (
    private val agentName: String,
    private val agentClass: Class<*>,
    private val agentInstance: Any,
    private val requireInterfaceDeserializationAnnotations: Boolean
): AgentValidator {
    private val logger = LoggerFactory.getLogger(AchievableGoalValidator::class.java)

    /**
     * Validate the agent and return the verification status accordingly.
     */
    override fun validate(agentScope: AgentScope): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        ReflectionUtils.doWithMethods(
            agentClass,
            { method ->
                if(!isActionMethod(logger,method, agentClass, requireInterfaceDeserializationAnnotations)) {
                    errors.add(
                        ValidationError(
                            code = ValidationErrorCodes.MISSING_ACTION_ANNOTATION,
                            message = "@Action annotation is missing on the method '${agentInstance.javaClass.name}.${method.name}' annotated with @AchievesGoal.",
                            severity = ValidationSeverity.ERROR,
                            location = ValidationLocation(
                                type = "Agent",
                                name = agentInstance.javaClass.name,
                                agentName = agentName,
                                component = method.name
                            )
                        )
                    )
                }
            },
            { method -> isMethodAnnotatedWithAchievesGoal(method) })
        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun isMethodAnnotatedWithAchievesGoal(method: Method ): Boolean {
        return method.isAnnotationPresent(AchievesGoal::class.java)
    }
}
